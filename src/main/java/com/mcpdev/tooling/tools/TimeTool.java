package com.mcpdev.tooling.tools;

import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import com.mcpdev.tooling.registry.Tool;
import com.mcpdev.tooling.registry.ToolInputException;
import com.mcpdev.tooling.registry.ToolResult;
import org.springframework.stereotype.Component;

/** Exercises the optional-argument path of the framework. */
@Component
public class TimeTool implements Tool<TimeTool.Args> {

    public record Args(Optional<String> timeZone) {
    }

    @Override
    public String name() {
        return "time";
    }

    @Override
    public String description()
    {
        return "Returns the server's current time. Optional IANA time zone, default UTC.";
    }

    @Override
    public Class<Args> requestType() {
        return Args.class;
    }

    @Override
    public ToolResult execute(Args args) {
        ZoneId zone;
        try {
            zone = args.timeZone()
                    .filter(tz -> !tz.isBlank())
                    .map(ZoneId::of)
                    .orElse(ZoneId.of("UTC"));
        } catch (DateTimeException e) {
            throw new ToolInputException("timeZone must be a valid IANA zone id, e.g. Europe/Berlin");
        }
        return ToolResult.text(OffsetDateTime.now(zone)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }
}
