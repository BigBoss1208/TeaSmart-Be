package vn.teasmart.backend.controller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.service.AdminCustomerService;
import vn.teasmart.backend.dto.request.AdminCustomerStatusRequest;
import vn.teasmart.backend.dto.response.*;
@RestController
@RequestMapping("/api/admin/customers")
public class AdminCustomerController {
    private final AdminCustomerService service;
    public AdminCustomerController(AdminCustomerService service){this.service=service;}
    @GetMapping public PageResponse<AdminCustomerResponse> list(
        @RequestParam(required=false) @Size(max=200) String keyword,
        @RequestParam(required=false) @Pattern(regexp="ACTIVE|INACTIVE") String status,
        @RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,
        @RequestParam(defaultValue="12") @Min(1) @Max(50) int size) {return service.list(keyword,status,page,size);}
    @GetMapping("/{userId}") public AdminCustomerResponse detail(@PathVariable @Positive Long userId){return service.detail(userId);}
    @PatchMapping("/{userId}/status") public AdminCustomerResponse status(@PathVariable @Positive Long userId,@Valid @RequestBody AdminCustomerStatusRequest request){return service.changeStatus(userId,request.status());}
    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<vn.teasmart.backend.exception.GlobalExceptionHandler.ErrorResponse> invalidCustomer(IllegalArgumentException exception) {
        return org.springframework.http.ResponseEntity.badRequest().body(new vn.teasmart.backend.exception.GlobalExceptionHandler.ErrorResponse("INVALID_CUSTOMER_REQUEST","Unsupported customer query or account status."));
    }
}
