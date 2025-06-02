package com.ywdrtt.conductor.secuity;

package com.yourcompany.app.security;

import com.yourcompany.app.config.SecurityRulesConfig;
import com.yourcompany.app.enums.ApplicationRole;
import com.yourcompany.app.enums.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class JwtTokenConverterConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtTokenConverterConfig.class);

    private final JwtGrantedAuthoritiesConverter defaultJwtGrantedAuthoritiesConverter =
            new JwtGrantedAuthoritiesConverter();

    private final SecurityRulesConfig securityRulesConfig;
    private final Map<String, String> externalToInternalRoleMap = new HashMap<>();

    public JwtTokenConverterConfig(SecurityRulesConfig securityRulesConfig) {
        this.securityRulesConfig = securityRulesConfig;

        for (Map.Entry<String, String> entry : securityRulesConfig.getRole().entrySet()) {
            externalToInternalRoleMap.put(entry.getValue(), entry.getKey().toUpperCase());
        }
        LOGGER.info("Initialized External to Internal Role Mapping: {}", externalToInternalRoleMap);
    }

    public Converter<Jwt, Collection<GrantedAuthority>> jwtGrantedAuthoritiesConverter() {
        return jwt -> {
            Collection<GrantedAuthority> authorities = defaultJwtGrantedAuthoritiesConverter.convert(jwt);
            if (authorities == null || authorities.isEmpty()) {
                authorities = new HashSet<>();
            } else {
                authorities = new HashSet<>(authorities);
            }

            LOGGER.debug("Default JwtGrantedAuthoritiesConverter Authorities (from 'scope'/'aud'): {} ", authorities);

            if (securityRulesConfig.getJwtClaims().getRolePaths() != null) {
                Set<SimpleGrantedAuthority> customAuthorities = securityRulesConfig.getJwtClaims().getRolePaths().stream()
                        .filter(StringUtils::hasText)
                        .flatMap(path -> {
                            List<String> rawRolesFromJwt = extractClaims(jwt, path);
                            LOGGER.debug("Extracted raw roles from JWT path '{}': {}", path, rawRolesFromJwt);
                            return rawRolesFromJwt.stream();
                        })
                        .flatMap(rawJwtRole -> {
                            Set<SimpleGrantedAuthority> generatedAuthorities = new HashSet<>();

                            String normalizedRawRole = rawJwtRole.toUpperCase();
                            generatedAuthorities.add(new SimpleGrantedAuthority("ROLE_" + normalizedRawRole));
                            LOGGER.debug("Added authority based on raw JWT role (for requestMatchers): ROLE_{}", normalizedRawRole);

                            String internalRoleName = externalToInternalRoleMap.get(rawJwtRole);
                            if (internalRoleName != null) {
                                generatedAuthorities.add(new SimpleGrantedAuthority("ROLE_" + internalRoleName));
                                LOGGER.debug("Added authority based on internal conceptual role mapping (for @PreAuthorize): ROLE_{}", internalRoleName);
                            } else {
                                if (!Arrays.stream(UserRole.values()).anyMatch(ur -> ur.name().equals(normalizedRawRole)) &&
                                        !Arrays.stream(ApplicationRole.values()).anyMatch(ar -> ar.name().equals(normalizedRawRole))) {
                                    LOGGER.warn("No specific mapping or direct enum match found for raw JWT role '{}'. Added as generic 'ROLE_{}' authority for requestMatchers. Consider defining a mapping for @PreAuthorize.", rawJwtRole, normalizedRawRole);
                                }
                            }
                            return generatedAuthorities.stream();
                        })
                        .collect(Collectors.toSet());

                LOGGER.debug("JwtGrantedAuthoritiesConverter Custom Authorities (after mapping and prefixing): {} ", customAuthorities);
                authorities.addAll(customAuthorities);
            }

            LOGGER.info("Final Merged Authorities for Spring Security: {} ", authorities);
            return authorities;
        };
    }

    private List<String> extractClaims(Jwt jwt, String path) {
        if (!StringUtils.hasLength(path)) return List.of();

        Object currentClaim = jwt.getClaims();
        String[] segments = path.split("\\.");
        for (String segment : segments) {
            if (currentClaim instanceof Map<?, ?> claims) {
                currentClaim = claims.get(segment);
                if (currentClaim == null) {
                    return List.of();
                }
            } else {
                LOGGER.warn("JWT claim path '{}' invalid. Expected a Map at segment '{}' but found '{}'",
                        path, segment, currentClaim != null ? currentClaim.getClass().getSimpleName() : "null");
                return List.of();
            }
        }

        if (currentClaim instanceof List<?> list) {
            try {
                return list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .collect(Collectors.toList());
            } catch (ClassCastException e) {
                LOGGER.warn("Failed to cast claim list elements to String for path '{}': {}", path, e.getMessage());
                return List.of();
            }
        } else if (currentClaim instanceof String) {
            return List.of((String) currentClaim);
        }
        return List.of();
    }
}