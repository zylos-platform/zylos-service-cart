package app.zylos.cart.adapter.in.rest;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import app.zylos.cart.application.exception.*;
import app.zylos.cart.domain.exception.CartClosedException;
import app.zylos.cart.domain.exception.CartDomainException;
import app.zylos.cart.domain.exception.CartLineNotFoundException;

/**
 * Translates domain, authorization, idempotency, and validation failures into RFC 9457 problems.
 */
@RestControllerAdvice
public class CartRestExceptionHandler {

    @ExceptionHandler({SkuNotFoundException.class, CartLineNotFoundException.class})
    ProblemDetail handleNotFound(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ActionDeniedException.class)
    ProblemDetail handleDenied(ActionDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(CartClosedException.class)
    ProblemDetail handleClosed(CartClosedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(CartContentionException.class)
    ProblemDetail handleContention(CartContentionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty("retryable", true);
        return problem;
    }

    @ExceptionHandler(IdempotencyInFlightException.class)
    ProblemDetail handleInFlight(IdempotencyInFlightException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty("retryable", true);
        return problem;
    }

    @ExceptionHandler({SkuNotPurchasableException.class, IdempotencyConflictException.class, CartDomainException.class})
    ProblemDetail handleUnprocessable(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Missing required header: " + ex.getHeaderName());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setDetail("Request validation failed");

        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "field", fieldError.getField(),
                        "message", String.valueOf(fieldError.getDefaultMessage())))
                .toList();

        problem.setProperty("errors", errors);
        return problem;
    }
}
