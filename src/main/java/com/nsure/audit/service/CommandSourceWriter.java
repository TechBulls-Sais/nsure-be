package com.nsure.audit.service;

import com.nsure.audit.domain.CommandSource;
import com.nsure.audit.repository.CommandSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommandSourceWriter {

    private final CommandSourceRepository commands;

    public CommandSourceWriter(CommandSourceRepository commands) {
        this.commands = commands;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommandSource saveNew(CommandSource command) {
        return commands.saveAndFlush(command);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public CommandSource saveSame(CommandSource command) {
        return commands.saveAndFlush(command);
    }
}
