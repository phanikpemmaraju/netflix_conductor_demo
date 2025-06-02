package com.ywdrtt.conductor.secuity;


import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Component
@ConfigurationProperties(prefix = "conductor.security")
public class SecurityRulesConfig {

    private Map<String, String> role = new HashMap<>();

    private JwtClaims jwtClaims = new JwtClaims();
    private List<EndpointRule> rules = new ArrayList<>();

    public Map<String, String> getRole() {
        return role;
    }

    public void setRole(Map<String, String> role) {
        this.role = role;
    }

    public JwtClaims getJwtClaims() {
        return jwtClaims;
    }

    public void setJwtClaims(JwtClaims jwtClaims) {
        this.jwtClaims = jwtClaims;
    }

    public List<EndpointRule> getRules() {
        return rules;
    }

    public void setRules(List<EndpointRule> rules) {
        this.rules = rules;
    }

    public static class EndpointRule {
        private String path;
        private List<String> methods = new ArrayList<>();
        private List<String> roles = new ArrayList<>();
        private boolean permitAll = false;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public List<String> getMethods() {
            return methods;
        }

        public void setMethods(List<String> methods) {
            this.methods = methods;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }

        public boolean isPermitAll() {
            return permitAll;
        }

        public void setPermitAll(boolean permitAll) {
            this.permitAll = permitAll;
        }

        public String[] getRolesArray() {
            return roles.toArray(String[]::new);
        }
    }

    public static class JwtClaims {
        private List<String> rolePaths = new ArrayList<>();

        public List<String> getRolePaths() {
            return rolePaths;
        }

        public void setRolePaths(List<String> rolePaths) {
            this.rolePaths = rolePaths;
        }
    }
}