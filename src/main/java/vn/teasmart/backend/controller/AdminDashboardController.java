package vn.teasmart.backend.controller;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.service.AdminDashboardService;
import vn.teasmart.backend.dto.response.AdminDashboardResponse;
@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {
    private final AdminDashboardService service;
    public AdminDashboardController(AdminDashboardService service){this.service=service;}
    @GetMapping public AdminDashboardResponse get(
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {return service.get(from,to);}
    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<vn.teasmart.backend.exception.GlobalExceptionHandler.ErrorResponse> invalidRange(IllegalArgumentException exception) {
        return org.springframework.http.ResponseEntity.badRequest().body(new vn.teasmart.backend.exception.GlobalExceptionHandler.ErrorResponse("INVALID_DATE_RANGE","Date range must be ordered, between years 2000 and 9998, and at most 366 days."));
    }
}
