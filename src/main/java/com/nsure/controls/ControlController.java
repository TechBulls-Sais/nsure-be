package com.nsure.controls;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/controls")
public class ControlController {

    private final ControlService controls;

    public ControlController(ControlService controls) {
        this.controls = controls;
    }

    @GetMapping
    public List<ControlView> list() {
        return controls.all().stream()
                .map(row -> new ControlView(
                        row.getControlKey(), row.getName(), row.getDescription(), row.getControlType(),
                        row.isEnabled(), row.getNumericValue(), row.getFailMode()))
                .toList();
    }

    public record ControlView(
            String key,
            String name,
            String description,
            String type,
            boolean enabled,
            BigDecimal numericValue,
            String failMode) {
    }
}
