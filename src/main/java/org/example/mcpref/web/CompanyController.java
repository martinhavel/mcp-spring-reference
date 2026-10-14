package org.example.mcpref.web;

import jakarta.servlet.http.HttpServletRequest;
import org.example.mcpref.governance.GovernedTool;
import org.example.mcpref.governance.GovernedToolService;
import org.example.mcpref.governance.ToolResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only API. Each endpoint maps to exactly one allowlisted tool; the client never names a
 * tool or passes free-form arguments.
 */
@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private final GovernedToolService service;

    public CompanyController(GovernedToolService service) {
        this.service = service;
    }

    @GetMapping("/{ico}")
    public ResponseEntity<ToolResult> lookup(@PathVariable String ico, Authentication user, HttpServletRequest request) {
        return respond(service.execute(GovernedTool.COMPANY_LOOKUP, ico, user, TraceIdFilter.current(request)));
    }

    @GetMapping("/{ico}/vat")
    public ResponseEntity<ToolResult> vatCheck(@PathVariable String ico, Authentication user, HttpServletRequest request) {
        return respond(service.execute(GovernedTool.VAT_CHECK, ico, user, TraceIdFilter.current(request)));
    }

    private static ResponseEntity<ToolResult> respond(ToolResult result) {
        return ResponseEntity.status(result.outcome().httpStatus()).body(result);
    }
}
