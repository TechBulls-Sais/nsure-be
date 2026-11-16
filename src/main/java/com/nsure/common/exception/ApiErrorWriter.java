package com.nsure.common.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;

/** Writes the standard error body from a servlet filter, where the controller advice does not reach. */
public final class ApiErrorWriter {

    private ApiErrorWriter() {
    }

    public static void write(HttpServletResponse response, ObjectMapper mapper, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), ApiError.of(status, code, message, List.of()));
    }
}
