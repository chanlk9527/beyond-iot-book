package com.nebula.iot.bootstrap.mqtt;

import com.nebula.iot.core.command.*;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.message.*;
import com.nebula.iot.core.model.Value;
import com.nebula.iot.device.api.DurableCommands;
import com.nebula.iot.device.infrastructure.JdbcDurableCommands;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class DurableCommandsTest {
    JdbcTemplate jdbc;TransactionTemplate tx;JdbcDurableCommands commands;
    final DeviceKey device=new DeviceKey(new TenantId("tenant-a"),new DeviceId("warmer"));
    final DurableCommands.Target target=new DurableCommands.Target("node-b","session-1",1);
    @BeforeEach void setup() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/cluster-device/V22__durable_commands.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));commands=new JdbcDurableCommands(jdbc,tx);
    }
    DeviceDownlink request(String id,Instant expires) {
        return new DeviceDownlink(new MessageId(id),device,"v1",Instant.now().minusSeconds(1),expires,Optional.empty(),Optional.empty(),new DownlinkBody.OperationInvoke(new CommandId("c-"+id),"setPower",Map.of("power",new Value.BooleanValue(true))));
    }
    @Test void commandAndOutboxCommitOrRollbackTogetherAndCanBeReadByAnotherInstance() {
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{commands.enqueue(request("rollback",Instant.now().plusSeconds(30)),"c-rollback","OPERATION");throw new IllegalStateException("crash");}));
        assertNull(commands.pending("rollback"));assertTrue(commands.outgoing(10).isEmpty());
        var command=request("saved",Instant.now().plusSeconds(30));commands.enqueue(command,"c-saved","OPERATION");
        var replacement=new JdbcDurableCommands(jdbc,tx);
        assertEquals(command,replacement.pending("saved").command());
        assertEquals("QUEUED",replacement.status(device,"saved").status());
        assertNull(replacement.status(new DeviceKey(new TenantId("other"),device.deviceId()),"saved"));
        var outgoing=commands.outgoing(10);assertEquals(1,outgoing.size());
        assertTrue(replacement.outgoing(10).isEmpty(),"Another relay must not immediately claim the same row");
        jdbc.update("UPDATE device_command_outbox SET claim_until=?",LocalDateTime.ofInstant(Instant.EPOCH,ZoneOffset.UTC));
        assertEquals(outgoing.getFirst().eventId(),replacement.outgoing(10).getFirst().eventId(),"Retry keeps the event ID");
    }
    @Test void repeatedRoutingAndDispatchCannotSendTwiceOrOverwriteFastFinalReply() {
        var command=request("one",Instant.now().plusSeconds(30));commands.enqueue(command,"c-one","OPERATION");
        commands.route("one",target);commands.route("one",target);
        assertTrue(commands.startSending("one",target,1));assertFalse(commands.startSending("one",target,1));
        var reply=new DeviceMessage(new MessageId("reply"),device,"v1",Instant.now(),Instant.now(),MessageOrigin.DEVICE,
                new DeviceMessageBody.CommandReply(new CommandId("c-one"),ExecutionStatus.SUCCEEDED,Optional.empty()),Optional.of(command.messageId()),Optional.of(ReplyResult.success()));
        commands.accept(reply);commands.delivered("one",true);commands.accept(reply);
        assertEquals("SUCCEEDED",commands.status(device,"one").status());
    }
    @Test void staleRouteRetriesAreBoundedButPossibleSocketSendsAreNeverRetried() {
        commands.enqueue(request("retry",Instant.now().plusSeconds(30)),"c-retry","OPERATION");
        for(int i=1;i<=3;i++){commands.route("retry",target);commands.reroute("retry",target,i);}
        assertEquals("ROUTE_FAILED",commands.status(device,"retry").status());
        commands.enqueue(request("unknown",Instant.now().plusSeconds(30)),"c-unknown","OPERATION");
        commands.route("unknown",target);assertTrue(commands.startSending("unknown",target,1));
        commands.delivered("unknown",false);commands.reroute("unknown",target,1);commands.route("unknown",target);
        assertEquals("UNKNOWN",commands.status(device,"unknown").status());
        assertFalse(commands.startSending("unknown",target,1));
    }
    @Test void expiredAndOfflineCommandsNeverEnterTheSocketSendState() {
        commands.enqueue(request("expired",Instant.now().minusMillis(10)),"c-expired","OPERATION");commands.route("expired",target);
        assertEquals("EXPIRED",commands.status(device,"expired").status());
        commands.enqueue(request("offline",Instant.now().plusSeconds(30)),"c-offline","OPERATION");commands.route("offline",null);
        assertEquals("OFFLINE",commands.status(device,"offline").status());
        assertFalse(commands.startSending("offline",target,1));
    }
    @Test void lateFinalFeedbackResolvesTimeoutWithoutErasingItsHistoryAndRejectsForgedCorrelations() {
        var command=request("late",Instant.now().plusSeconds(30));commands.enqueue(command,"c-late","OPERATION");
        commands.route("late",target);commands.startSending("late",target,1);
        jdbc.update("UPDATE device_command SET expires_at=? WHERE message_id='late'",LocalDateTime.ofInstant(Instant.now().minusSeconds(1),ZoneOffset.UTC));
        commands.sweep();assertEquals("TIMED_OUT",commands.status(device,"late").status());
        var wrongDevice=new DeviceKey(new TenantId("other"),device.deviceId());
        commands.accept(new DeviceMessage(new MessageId("forged"),wrongDevice,"v1",Instant.now(),Instant.now(),MessageOrigin.DEVICE,
                new DeviceMessageBody.CommandReply(new CommandId("c-late"),ExecutionStatus.SUCCEEDED,Optional.empty()),Optional.of(command.messageId()),Optional.of(ReplyResult.success())));
        assertEquals("TIMED_OUT",commands.status(device,"late").status());
        commands.accept(new DeviceMessage(new MessageId("accepted"),device,"v1",Instant.now(),Instant.now(),MessageOrigin.DEVICE,
                new DeviceMessageBody.CommandReply(new CommandId("c-late"),ExecutionStatus.ACCEPTED,Optional.empty()),Optional.of(command.messageId()),Optional.of(ReplyResult.success())));
        assertEquals("TIMED_OUT",commands.status(device,"late").status());
        commands.accept(new DeviceMessage(new MessageId("final"),device,"v1",Instant.now(),Instant.now(),MessageOrigin.DEVICE,
                new DeviceMessageBody.CommandReply(new CommandId("c-late"),ExecutionStatus.SUCCEEDED,Optional.empty()),Optional.of(command.messageId()),Optional.of(ReplyResult.success())));
        assertEquals("SUCCEEDED",commands.status(device,"late").status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_command_transition WHERE message_id='late' AND to_state='TIMED_OUT'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_command_transition WHERE message_id='late' AND from_state='TIMED_OUT' AND to_state='SUCCEEDED'",Integer.class));
    }
}
