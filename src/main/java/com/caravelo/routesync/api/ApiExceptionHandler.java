package com.caravelo.routesync.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.caravelo.routesync.sync.SyncInProgressException;
import com.caravelo.routesync.sync.TenantDisabledException;
import com.caravelo.routesync.sync.TenantNotFoundException;

/** Errors as RFC 9457 problem+json; a bad query parameter is named in {@code parameter} (SPEC §6.2). */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(TenantNotFoundException.class)
    ProblemDetail notFound(TenantNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({SyncInProgressException.class, TenantDisabledException.class})
    ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InvalidParameterException.class)
    ProblemDetail invalidParameter(InvalidParameterException e) {
        return badRequest(e.parameter(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail typeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest(e.getName(), "Invalid value for " + e.getName() + ": " + e.getValue());
    }

    private static ProblemDetail badRequest(String parameter, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("parameter", parameter);
        return problem;
    }
}
