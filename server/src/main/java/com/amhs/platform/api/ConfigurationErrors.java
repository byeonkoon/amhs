package com.amhs.platform.api;
import com.amhs.platform.ConfigurationException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.*;
@RestControllerAdvice
class ConfigurationErrors {
 private ResponseEntity<Map<String,String>> error(int status,String code){
  return ResponseEntity.status(status).body(Map.of("code",code,"message",code,"traceId",UUID.randomUUID().toString()));
 }
 @ExceptionHandler(ConfigurationException.class)
 ResponseEntity<?> domain(ConfigurationException e){return error(e.status(),e.getMessage());}
 @ExceptionHandler(DuplicateKeyException.class)
 ResponseEntity<?> duplicate(DuplicateKeyException e){return error(409,"DUPLICATE_CONFIGURATION");}
 @ExceptionHandler(DataIntegrityViolationException.class)
 ResponseEntity<?> constraint(DataIntegrityViolationException e){return error(422,"CONFIGURATION_CONSTRAINT");}
 @ExceptionHandler(HttpMessageNotReadableException.class)
 ResponseEntity<?> malformed(HttpMessageNotReadableException e){return error(400,"INVALID_JSON");}
}
