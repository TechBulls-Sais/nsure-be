package com.nsure.security.web;

import com.nsure.security.service.OfficeQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/offices")
public class OfficeController {

    private final OfficeQueryService offices;

    public OfficeController(OfficeQueryService offices) {
        this.offices = offices;
    }

    @GetMapping
    public List<OfficeResponse> list() {
        return offices.list();
    }
}
