package com.example.demo.api;

import com.example.demo.service.CleanupSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/cleanups/session")
public class CleanupSessionController {
    private final CleanupSession cleanup;
    public CleanupSessionController(CleanupSession cleanup){this.cleanup=cleanup;}
    public record Version(int version){}
    public record Decision(String selected,String candidate,String action,int version){}
    @PostMapping("/open") public Map<String,Object> open(HttpServletRequest request){return cleanup.open(request.getSession());}
    @GetMapping("/status") public Map<String,Object> status(HttpServletRequest request){return cleanup.status(request.getSession());}
    @GetMapping public Map<String,Object> page(HttpServletRequest request,@RequestParam Map<String,String> params){return cleanup.page(request.getSession(),params);}
    @PostMapping("/acknowledge") public Map<String,Object> acknowledge(HttpServletRequest request,@RequestBody Version input){return cleanup.acknowledge(request.getSession(),input.version());}
    @GetMapping("/items/{selected}") public Map<String,Object> details(HttpServletRequest request,@PathVariable String selected){return cleanup.details(request.getSession(),selected);}
    @PostMapping("/decision") public Map<String,Object> decide(HttpServletRequest request,@RequestBody Decision input){return cleanup.decide(request.getSession(),input.selected(),input.candidate(),input.action(),input.version());}
    @PostMapping("/save") public Map<String,Object> save(HttpServletRequest request,@RequestBody Version input){return cleanup.save(request.getSession(),input.version());}
    @PostMapping("/discard") public Map<String,Object> discard(HttpServletRequest request){return cleanup.discard(request.getSession());}
}
