package com.ywdrtt.conductor.working;

package com.yourcompany.yourconductorapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Configuration
public class JwtConverterConfig {

    @Value("${jwt.claims.role-paths:}")
    private List<String> roleClaimPaths;

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(jwtGrantedAuthoritiesConverter());
        return jwtAuthenticationConverter;
    }

    private Converter<Jwt, Collection<GrantedAuthority>> jwtGrantedAuthoritiesConverter() {
        return jwt -> {
            Set<GrantedAuthority> authorities = Stream.empty().collect(Collectors.toSet());

            JwtGrantedAuthoritiesConverter defaultAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
            authorities.addAll(defaultAuthoritiesConverter.convert(jwt));

            for (String rolePath : roleClaimPaths) {
                List<String> rolesFromPath = extractListClaimByPath(jwt, rolePath.trim());
                if (rolesFromPath != null && !rolesFromPath.isEmpty()) {
                    authorities.addAll(
                            rolesFromPath.stream()
                                    .map(roleName -> new SimpleGrantedAuthority("ROLE_" + roleName.toUpperCase()))
                                    .collect(Collectors.toSet())
                    );
                }
            }

            return authorities;
        };
    }

    private List<String> extractListClaimByPath(Jwt jwt, String path) {
        if (path == null || path.isEmpty()) {
            return Collections.emptyList();
        }

        String[] pathSegments = path.split("\\.");
        Object currentClaim = jwt.getClaims();

        for (int i = 0; i < pathSegments.length; i++) {
            String segment = pathSegments[i];

            if (currentClaim instanceof Map) {
                Map<String, Object> currentMap = (Map<String, Object>) currentClaim;
                currentClaim = currentMap.get(segment);

                if (currentClaim == null) {
                    return Collections.emptyList();
                }
            } else {
                if (i < pathSegments.length - 1) {
                    System.err.println("Warning: JWT claim path '" + path + "' invalid. Expected a Map at segment '" + segment + "' but found " + (currentClaim != null ? currentClaim.getClass().getSimpleName() : "null"));
                    return Collections.emptyList();
                }
            }
        }

        if (currentClaim instanceof List) {
            try {
                return ((List<?>) currentClaim).stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .collect(Collectors.toList());
            } catch (ClassCastException e) {
                System.err.println("Warning: JWT claim at path '" + path + "' is a List but contains non-String elements. " + e.getMessage());
                return Collections.emptyList();
            }
        } else if (currentClaim instanceof String) {
            return Collections.singletonList((String) currentClaim);
        } else {
            System.err.println("Warning: JWT claim at path '" + path + "' is not a List<String> or String. Found: " + (currentClaim != null ? currentClaim.getClass().getSimpleName() : "null"));
            return Collections.emptyList();
        }
    }
}
