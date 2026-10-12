package com.nsure.audit;

import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.web.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommandWorker {

    private static final Logger log = LoggerFactory.getLogger(CommandWorker.class);

    private final CommandHandlerRegistry handlers;
    private final CommandSourceStore store;

    public CommandWorker(CommandHandlerRegistry handlers, CommandSourceStore store) {
        this.handlers = handlers;
        this.store = store;
    }

    @Transactional
    public ExecutedCommand execute(Long commandId, CommandRequest request, boolean needsChecker) {
        try {
            CommandResult result = handlers.get(request.entityName(), request.actionName()).handle(request);
            if (needsChecker) {
                store.markAwaiting(commandId);
                log.info("Command {} awaiting approval", commandId);
                throw new AwaitingApprovalException(commandId);
            }
            store.markProcessed(commandId, result.resourceId(), result.json());
            log.info("Command {} processed", commandId);
            return new ExecutedCommand(200, result.json(), commandId, result.resourceId());
        } catch (AwaitingApprovalException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            Failure failure = failure(ex);
            store.markError(commandId, failure.status(), failure.message());
            if (failure.status() >= 500) {
                log.warn("Command {} failed", commandId, ex);
            } else {
                log.info("Command {} rejected with status {}", commandId, failure.status());
            }
            if (ex instanceof DataIntegrityViolationException) {
                throw new IllegalStateException(failure.message());
            }
            throw ex;
        }
    }

    private Failure failure(RuntimeException ex) {
        if (ex instanceof DataIntegrityViolationException violation) {
            return new Failure(409, integrityMessage(violation));
        }
        if (ex instanceof NoAuthorizationException) {
            return new Failure(403, message(ex));
        }
        if (ex instanceof ResourceNotFoundException) {
            return new Failure(404, message(ex));
        }
        if (ex instanceof IllegalArgumentException) {
            return new Failure(400, message(ex));
        }
        if (ex instanceof IllegalStateException) {
            return new Failure(409, message(ex));
        }
        return new Failure(500, message(ex));
    }

    private String integrityMessage(DataIntegrityViolationException violation) {
        String hint = violation.getMostSpecificCause().getMessage();
        if (hint != null) {
            String lower = hint.toLowerCase();
            if (lower.contains("username")) {
                return "Username is already in use";
            }
            if (lower.contains("uk_app_role_name") || lower.contains("app_role")) {
                return "Name is already in use";
            }
        }
        return "Duplicate or invalid reference";
    }

    private String message(RuntimeException ex) {
        String text = ex.getMessage();
        if (text == null || text.isBlank()) {
            text = ex.getClass().getSimpleName();
        }
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }

    private record Failure(int status, String message) {
    }
}
