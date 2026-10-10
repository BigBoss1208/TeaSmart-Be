package vn.teasmart.backend.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import vn.teasmart.backend.service.TeaAdvisorService;
import vn.teasmart.backend.dto.request.TeaAdviceRequest;
import vn.teasmart.backend.dto.response.TeaAdviceResponse;
import vn.teasmart.backend.exception.GlobalExceptionHandler.ErrorResponse;

@RestController
@RequestMapping("/api/chatbot")
public class TeaAdvisorController {
    private final TeaAdvisorService advisor;
    public TeaAdvisorController(TeaAdvisorService advisor){this.advisor=advisor;}
    @PostMapping("/advice") public TeaAdviceResponse advice(@Valid @RequestBody TeaAdviceRequest request) {return advisor.advise(request);}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<ErrorResponse> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_PREFERENCES","Invalid tea preference or price range."));
    }
}
