package vn.teasmart.backend.service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.entity.User;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.repository.*;
import vn.teasmart.backend.dto.response.*;
@Service
@Transactional(readOnly=true)
public class AdminCustomerService {
    private final AdminCustomerRepository customers;
    private final AdminReportingRepository reporting;
    public AdminCustomerService(AdminCustomerRepository customers,AdminReportingRepository reporting) {this.customers=customers;this.reporting=reporting;}
    public PageResponse<AdminCustomerResponse> list(String keyword,String status,int page,int size) {
        String literal=(keyword==null?"":keyword.trim()).toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_");
        var result=customers.searchCustomers(status,"%"+literal+"%",PageRequest.of(page,size,Sort.by("userId").descending()));
        var totals=reporting.customerTotals(result.getContent().stream().map(User::getUserId).toList());
        return new PageResponse<>(result.getContent().stream().map(u->response(u,totals)).toList(),page,size,result.getTotalElements(),result.getTotalPages());
    }
    public AdminCustomerResponse detail(Long id) {
        var u=customers.findByUserIdAndRole(id,"CUSTOMER").orElseThrow(()->new ResourceNotFoundException("Customer not found."));
        return response(u,reporting.customerTotals(List.of(id)));
    }
    @Transactional
    public AdminCustomerResponse changeStatus(Long id,String status) {
        // Same User-first lock as Customer Cart/checkout. No Order or Payment writes.
        var u=customers.findLockedByUserIdAndRole(id,"CUSTOMER").orElseThrow(()->new ResourceNotFoundException("Customer not found."));
        if(!List.of("ACTIVE","INACTIVE").contains(u.getStatus())||!List.of("ACTIVE","INACTIVE").contains(status))
            throw new IllegalArgumentException("Unsupported customer status.");
        if(!status.equals(u.getStatus())) { u.setStatus(status);u.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).withNano(0));customers.flush(); }
        return response(u,reporting.customerTotals(List.of(id)));
    }
    private AdminCustomerResponse response(User u,Map<Long,AdminReportingRepository.CustomerTotals> totals) {
        var t=totals.getOrDefault(u.getUserId(),new AdminReportingRepository.CustomerTotals(0,BigDecimal.ZERO));
        return new AdminCustomerResponse(u.getUserId(),u.getFullName(),u.getEmail(),u.getPhone(),u.getStatus(),u.getCreatedAt(),u.getUpdatedAt(),t.ordersCount(),t.totalSpent());
    }
}
