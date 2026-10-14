package com.billtracker.web;

import com.billtracker.bill.BillService.BillNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** JSON errors for the API; takes precedence over the HTML error pages in {@link ErrorHandling}. */
@RestControllerAdvice(assignableTypes = ApiController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiErrorHandling {

    @ExceptionHandler(BillNotFoundException.class)
    public ProblemDetail notFound(BillNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
