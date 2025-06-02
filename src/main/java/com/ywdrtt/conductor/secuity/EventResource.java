package com.ywdrtt.conductor.secuity;

// File: conductor-oss/conductor/conductor-e2c76e689bc15544255f39c815df05ee0cf7fc06/rest/src/main/java/com/netflix/conductor/rest/controllers/EventResource.java
package com.netflix.conductor.rest.controllers;

import com.netflix.conductor.common.metadata.events.EventHandler;
import com.netflix.conductor.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.yourcompany.app.enums.Permission; // Assuming your Permission enum is here, adjust if path differs
import org.springframework.security.access.prepost.PreAuthorize;

import javax.inject.Inject;
import java.util.List;

@RestController
@RequestMapping("/event")
@SecurityRequirement(name = "api_key")
public class EventResource {

    private final EventService eventService;

    @Inject
    public EventResource(EventService eventService) {
        this.eventService = eventService;
    }

    @Operation(summary = "Add a new event handler")
    @PostMapping
    @PreAuthorize("hasPermission(null, 'EVENT', T(com.yourcompany.app.enums.Permission).EVENT_CREATE.name())")
    public void addEventHandler(@RequestBody EventHandler eventHandler) {
        eventService.addEventHandler(eventHandler);
    }

    @Operation(summary = "Update an existing event handler")
    @PutMapping
    @PreAuthorize("hasPermission(null, 'EVENT', T(com.yourcompany.app.enums.Permission).EVENT_UPDATE.name())") // Assuming updates are general or handled by internal logic based on name
    public void updateEventHandler(@RequestBody EventHandler eventHandler) {
        eventService.updateEventHandler(eventHandler);
    }

    @Operation(summary = "Get all event handlers")
    @GetMapping
    @PreAuthorize("hasPermission(null, 'EVENT', T(com.yourcompany.app.enums.Permission).EVENT_READ.name())")
    public List<EventHandler> getEventHandlers() {
        return eventService.getEventHandlers();
    }

    @Operation(summary = "Get event handlers for a specific event")
    @GetMapping("/events/{event}")
    @PreAuthorize("hasPermission(null, 'EVENT', T(com.yourcompany.app.enums.Permission).EVENT_READ.name())")
    public List<EventHandler> getEventHandlersForEvent(@RequestParam("event") String event,
                                                       @RequestParam(value = "activeOnly", defaultValue = "true") boolean activeOnly) {
        return eventService.getEventHandlersForEvent(event, activeOnly);
    }

    @Operation(summary = "Remove an event handler")
    @DeleteMapping("/{name}")
    @PreAuthorize("hasPermission(null, 'EVENT', T(com.yourcompany.app.enums.Permission).EVENT_DELETE.name())") // Assuming name is sufficient for deletion check
    public void removeEventHandler(@PathVariable("name") String name) {
        eventService.removeEventHandler(name);
    }
}