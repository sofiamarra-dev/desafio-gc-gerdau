package com.example.demo.api;
import com.example.demo.service.RankedCleanup;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/cleanups/ranked")
public class RankedCleanupController {
    private final RankedCleanup cleanup;
    public RankedCleanupController(RankedCleanup cleanup){this.cleanup=cleanup;}
    public record Create(Boolean skip_verified){}
    public record Decision(String selected,String candidate,String action,int version){}
    public record Apply(boolean confirmed){}
    @GetMapping public List<Map<String,Object>> list(){return cleanup.list();}
    @PostMapping public Map<String,Object> create(@RequestBody Create input){return cleanup.create(!Boolean.FALSE.equals(input.skip_verified()));}
    @GetMapping("/{id}") public Map<String,Object> page(@PathVariable String id,@RequestParam Map<String,String> params){return cleanup.page(id,params);}
    @GetMapping("/{id}/items/{selected}") public Map<String,Object> details(@PathVariable String id,@PathVariable String selected){return cleanup.details(id,selected);}
    @PostMapping("/{id}/decision") public Map<String,Object> decide(@PathVariable String id,@RequestBody Decision input){return cleanup.decide(id,input.selected(),input.candidate(),input.action(),input.version());}
    @PostMapping("/{id}/apply") public Map<String,Object> apply(@PathVariable String id,@RequestBody Apply input){return cleanup.apply(id,input.confirmed());}
}
