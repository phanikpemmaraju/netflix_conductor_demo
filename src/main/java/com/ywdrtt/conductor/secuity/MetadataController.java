package com.ywdrtt.conductor.secuity;

package com.yourcompany.app.controller;

import com.yourcompany.app.enums.ApplicationRole;
import com.yourcompany.app.enums.Permission;
import com.yourcompany.app.enums.UserRole;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/metadata")
public class MetadataController {

    private final MetadataService metadataService;

    public MetadataController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    private static String roles(Enum<?>... roles) {
        return Arrays.stream(roles)
                .map(Enum::name)
                .collect(Collectors.joining("','", "'", "'"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Metadata', '" + Permission.METADATA_READ + "') or " +
            "hasAnyRole(" + roles(UserRole.ADMIN, ApplicationRole.METADATA_API) + ")")
    public Metadata getMetadata(@PathVariable Long id) {
        return metadataService.findById(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole(" + roles(UserRole.METADATA_MANAGER, UserRole.ADMIN, ApplicationRole.METADATA_API) + ")")
    public Metadata createMetadata(@RequestBody Metadata newMetadata) {
        return metadataService.save(newMetadata);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Metadata', '" + Permission.METADATA_UPDATE + "') or " +
            "hasAnyRole(" + roles(UserRole.ADMIN, ApplicationRole.METADATA_API) + ")")
    public Metadata updateMetadata(@PathVariable Long id, @RequestBody Metadata updatedMetadata) {
        return metadataService.updateMetadata(id, updatedMetadata);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Metadata', '" + Permission.METADATA_DELETE + "') or " +
            "hasAnyRole(" + roles(UserRole.ADMIN, ApplicationRole.METADATA_API) + ")")
    public void deleteMetadata(@PathVariable Long id) {
        metadataService.deleteMetadata(id);
    }
}
