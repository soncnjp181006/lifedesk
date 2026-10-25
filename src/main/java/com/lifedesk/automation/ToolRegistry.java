package com.lifedesk.automation;

import com.lifedesk.common.AppException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of agent tools. Anything not registered here cannot be executed
 * by the agent — in particular there is deliberately NO shell/process tool.
 */
public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        ToolDescriptor d = tool.descriptor();
        if (tools.containsKey(d.name())) {
            throw new AppException("Tool trùng tên: " + d.name());
        }
        tools.put(d.name(), tool);
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Map<String, Tool> all() {
        return java.util.Collections.unmodifiableMap(tools);
    }
}
