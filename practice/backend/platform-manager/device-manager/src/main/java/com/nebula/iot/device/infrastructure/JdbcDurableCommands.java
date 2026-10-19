package com.nebula.iot.device.infrastructure;

import com.nebula.iot.cluster.DatabaseTime;
import com.nebula.iot.core.command.*;
import com.nebula.iot.core.device.DeviceKey;
import com.nebula.iot.core.message.*;
import com.nebula.iot.device.api.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Command + outbound intent commit atomically. Dispatch is claimed before touching a socket, never blindly retried. */
public final class JdbcDurableCommands implements DurableCommands {
    private record Row(DeviceDownlink command,DownlinkStatus status,Target target,int attempt) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DatabaseTime time;
    private final CommandJson json=new CommandJson();
    public JdbcDurableCommands(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;time=new DatabaseTime(jdbc);}
    @Override public DownlinkStatus enqueue(DeviceDownlink command,String commandId,String kind,String actor) {
        if(actor==null||actor.isBlank()||actor.length()>128)throw new IllegalArgumentException("Command actor required");
        return tx.execute(status->{
            jdbc.update("INSERT INTO device_command(message_id,tenant_id,device_id,command_id,command_kind,requested_by,request_json,command_state,result_code,result_message,created_at,expires_at,attempt,target_node,target_session,target_epoch) VALUES (?,?,?,?,?,?,?,'QUEUED',NULL,?,?,?,0,NULL,NULL,NULL)",
                    command.messageId().value(),command.device().tenantId().value(),command.device().deviceId().value(),commandId,kind,actor,json.encode(command),"命令已持久化，等待路由",utc(command.createdAt()),utc(command.expiresAt()));
            history(command.messageId().value(),null,"QUEUED",null,"命令及待发布工作已保存");
            outbound(command,"internal/command/route");return row(command.messageId().value(),false).status();
        });
    }
    @Override public DownlinkStatus status(DeviceKey key,String id) {

        return tx.execute(status->{
            var row=row(id,true);
            if(row==null || !row.command().device().equals(key))return null;
            if(!row.status().expiresAt().isAfter(time.now())) {
                if(Set.of("QUEUED","ROUTED").contains(row.status().status()))change(id,"EXPIRED","EXPIRED","命令过期且未发送");
                else if(Set.of("SENDING","DELIVERED","ACCEPTED").contains(row.status().status()))change(id,"TIMED_OUT","REPLY_TIMEOUT","等待设备反馈超时，执行结果未知，不自动重发");
            }
            return row(id,false).status();
        });
    }
    @Override public Dispatch pending(String id) {
        var row=row(id,false);return row==null?null:new Dispatch(row.command(),row.target(),row.attempt());
    }
    @Override public void route(String id,Target target) {
        tx.executeWithoutResult(status->{
            var row=row(id,true);if(row==null || !row.status().status().equals("QUEUED"))return;
            if(row.command().isExpiredAt(time.now())){change(id,"EXPIRED","EXPIRED","命令已过期，未发送");return;}
            if(target==null){change(id,"OFFLINE","DEVICE_OFFLINE","设备没有有效在线会话，未发送");return;}
            change(id,"ROUTED",null,"已按当前会话归属路由");
            jdbc.update("UPDATE device_command SET target_node=?,target_session=?,target_epoch=?,attempt=attempt+1 WHERE message_id=?",
                    target.nodeId(),target.sessionId(),target.epoch(),id);
            outbound(row.command(),"internal/command/dispatch");
        });
    }
    @Override public boolean startSending(String id,Target target,int attempt) {
        return Boolean.TRUE.equals(tx.execute(status->{
            var row=row(id,true);
            if(row==null || !row.status().status().equals("ROUTED") || !Objects.equals(row.target(),target) || row.attempt()!=attempt)return false;
            if(row.command().isExpiredAt(time.now())){change(id,"EXPIRED","EXPIRED","命令已过期，未发送");return false;}
            change(id,"SENDING",null,"已开始投递，尚未确认设备执行");return true;
        }));
    }
    @Override public void reroute(String id,Target target,int attempt) {
        tx.executeWithoutResult(status->{
            var row=row(id,true);
            if(row==null || !row.status().status().equals("ROUTED") || !Objects.equals(row.target(),target) || row.attempt()!=attempt)return;
            if(row.attempt()>=3){change(id,"ROUTE_FAILED","SESSION_CHANGED","会话迁移超过路由重查次数，未发送");return;}
            change(id,"QUEUED",null,"会话已迁移，重新路由");outbound(row.command(),"internal/command/route");
        });
    }
    @Override public void delivered(String id,boolean confirmed) {
        tx.executeWithoutResult(status->{
            var row=row(id,true);if(row==null || !row.status().status().equals("SENDING"))return;
            change(id,confirmed?"DELIVERED":"UNKNOWN",confirmed?null:"DELIVERY_UNCONFIRMED",confirmed?"传输已确认，等待设备执行反馈":"传输结果未知，不自动重发");
        });
    }
    @Override public void accept(DeviceMessage message) {
        if(message.origin()!=MessageOrigin.DEVICE || message.replyTo().isEmpty() || message.result().isEmpty())return;
        tx.executeWithoutResult(status->{
            var row=row(message.replyTo().get().value(),true);
            if(row==null || !row.command().device().equals(message.device()) || !row.command().modelVersion().equals(message.modelVersion())
                    || !Set.of("SENDING","DELIVERED","ACCEPTED","UNKNOWN","TIMED_OUT").contains(row.status().status()))return;
            var result=message.result().get();String next=null;
            if(row.command().body() instanceof DownlinkBody.OperationInvoke invoke && message.body() instanceof DeviceMessageBody.CommandReply reply && invoke.commandId().equals(reply.commandId()))next=reply.status().name();
            if(row.command().body() instanceof DownlinkBody.PropertyWrite && (message.body() instanceof DeviceMessageBody.PropertyWriteReply
                    || message.body() instanceof DeviceMessageBody.RequestFailure failure && failure.kind()==DeviceMessageBody.RequestKind.PROPERTY_WRITE))next=result.isSuccess()?"SUCCEEDED":"FAILED";
            if(next==null)return;
            // Late ACCEPTED cannot erase a timeout/unknown history. A final feedback may resolve it.
            if(next.equals("ACCEPTED") && Set.of("UNKNOWN","TIMED_OUT").contains(row.status().status()))return;
            change(row.command().messageId().value(),next,result.code(),result.message().orElse(next));
        });
    }
    @Override public List<Outbound> outgoing(int limit) {
        if(limit<1||limit>100)throw new IllegalArgumentException("Outbound batch out of range");
        var rows=jdbc.query("SELECT * FROM device_command_outbox WHERE published=FALSE AND (claim_until IS NULL OR claim_until<=?) ORDER BY created_at,event_id LIMIT ?",
                (rs,index)->new Outbound(rs.getString("event_id"),rs.getString("topic"),rs.getString("tenant_id"),rs.getString("device_id"),rs.getString("message_id"),rs.getString("payload")),utc(time.now()),limit);
        var claimed=new ArrayList<Outbound>();
        for(var event:rows) {
            var now=time.now();
            if(jdbc.update("UPDATE device_command_outbox SET claim_until=? WHERE event_id=? AND published=FALSE AND (claim_until IS NULL OR claim_until<=?)",
                    utc(now.plusSeconds(10)),event.eventId(),utc(now))==1)claimed.add(event);
        }
        return claimed;
    }
    @Override public void published(String id){jdbc.update("UPDATE device_command_outbox SET published=TRUE WHERE event_id=?",id);}
    @Override public void sweep() {
        var now=utc(time.now());
        var expired=jdbc.queryForList("SELECT message_id FROM device_command WHERE command_state IN ('QUEUED','ROUTED','SENDING','DELIVERED','ACCEPTED') AND expires_at<=? ORDER BY expires_at LIMIT 100",String.class,now);
        for(var id:expired)tx.executeWithoutResult(status->{
            var row=row(id,true);if(row==null || row.status().expiresAt().isAfter(time.now()))return;
            if(Set.of("QUEUED","ROUTED").contains(row.status().status()))change(id,"EXPIRED","EXPIRED","命令过期且未发送");
            else if(Set.of("SENDING","DELIVERED","ACCEPTED").contains(row.status().status()))change(id,"TIMED_OUT","REPLY_TIMEOUT","等待设备反馈超时，执行结果未知，不自动重发");
        });
    }
    @Override public void recoverRoutes(java.util.function.Function<DeviceKey,Target> owners) {
        // Broadcast consumers start at latest. Re-signal only work proven not claimed for socket send.
        var waiting=jdbc.queryForList("SELECT message_id FROM device_command WHERE command_state='ROUTED' AND (signal_at IS NULL OR signal_at<=?) ORDER BY created_at LIMIT 20",String.class,utc(time.now().minusSeconds(3)));
        for(var id:waiting)tx.executeWithoutResult(status->{
            var row=row(id,true);
            if(row==null || !row.status().status().equals("ROUTED"))return;
            var owner=owners.apply(row.command().device());
            if(!Objects.equals(owner,row.target()))reroute(id,row.target(),row.attempt());
            else outbound(row.command(),"internal/command/dispatch");
        });
    }
    private void outbound(DeviceDownlink command,String topic) {
        jdbc.update("INSERT INTO device_command_outbox(event_id,topic,tenant_id,device_id,message_id,payload,published,created_at) VALUES (?,?,?,?,?,?,FALSE,?)",
                UUID.randomUUID().toString(),topic,command.device().tenantId().value(),command.device().deviceId().value(),command.messageId().value(),"v1",utc(time.now()));
        jdbc.update("UPDATE device_command SET signal_at=? WHERE message_id=?",utc(time.now()),command.messageId().value());
    }
    private void change(String id,String state,String code,String message){
        var previous=row(id,false);
        if(previous!=null && previous.status().status().equals(state) && Objects.equals(previous.status().code(),code))return;
        jdbc.update("UPDATE device_command SET command_state=?,result_code=?,result_message=? WHERE message_id=?",state,code,message,id);
        history(id,previous==null?null:previous.status().status(),state,code,message);
    }
    private void history(String id,String from,String to,String code,String message){
        jdbc.update("INSERT INTO device_command_transition(transition_id,message_id,from_state,to_state,result_code,detail,occurred_at) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),id,from,to,code,message,utc(time.now()));
    }
    private Row row(String id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM device_command WHERE message_id=?"+(lock?" FOR UPDATE":""),(rs,index)->{
            var request=json.decode(rs.getString("request_json"));
            var status=new DownlinkStatus(id,rs.getString("command_id"),rs.getString("command_kind"),rs.getString("command_state"),rs.getString("result_code"),rs.getString("result_message"),instant(rs,"created_at"),instant(rs,"expires_at"));
            var target=rs.getString("target_node")==null?null:new Target(rs.getString("target_node"),rs.getString("target_session"),rs.getLong("target_epoch"));
            return new Row(request,status,target,rs.getInt("attempt"));
        },id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private static LocalDateTime utc(Instant instant){return LocalDateTime.ofInstant(instant,ZoneOffset.UTC);}
    private static Instant instant(ResultSet rs,String key)throws SQLException{return rs.getObject(key,LocalDateTime.class).toInstant(ZoneOffset.UTC);}
}
