package com.runevault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/admin")
public class PlatformApi {
 @GetMapping("/status")
 @PreAuthorize("hasAuthority('PERM_admin:platform')")
 public Map<String,String> status() { return Map.of("mode","simulation","storage","in-memory"); }
}
