package com.muse.connect.controller;

import com.muse.connect.constant.WebViewConstants;
import com.muse.connect.model.RelayResponse;
import com.muse.connect.service.ConnectorException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler {

    @ExceptionHandler(ConnectorException.class)
    public ResponseEntity<RelayResponse> handleConnectorException(ConnectorException ex) {
        return ResponseEntity.status(ex.getStatus()).body(
                RelayResponse.fail(null, null, ex.getStatus().value(), 0, ex.getMessage(), null)
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<RelayResponse> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(WebViewConstants.VALIDATION_ERROR_DELIMITER));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                RelayResponse.fail(null, null, 400, 0, msg, null)
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RelayResponse> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                RelayResponse.fail(null, null, 400, 0, ex.getMessage(), null)
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RelayResponse> handleOther(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                RelayResponse.fail(null, null, 500, 0, ex.getMessage(), null)
        );
    }
}
