package com.nebula.iot.bootstrap.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

final class SecurityProblemWriter {
    private SecurityProblemWriter() {}
    static void write(ObjectMapper mapper, HttpServletResponse response, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        mapper.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status,
                status == HttpStatus.UNAUTHORIZED ? "Authentication required or credentials invalid" : "Access denied"));
    }
}
