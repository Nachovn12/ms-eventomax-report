package cl.duoc.eventomax.report.controller.advice;

import cl.duoc.eventomax.report.exception.InvalidReportRangeException;
import cl.duoc.eventomax.report.exception.TopServicesNotImplementedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;

@RestControllerAdvice
public class ReportExceptionHandler {

    @ExceptionHandler(InvalidReportRangeException.class)
    public ProblemDetail handleInvalidReportRange(InvalidReportRangeException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "El valor del parámetro 'range' no es válido."
        );
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Solicitud inválida");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("permittedValues", List.of("last24h", "last7d"));
        return problem;
    }

    @ExceptionHandler(TopServicesNotImplementedException.class)
    public ProblemDetail handleTopServicesNotImplemented(TopServicesNotImplementedException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_IMPLEMENTED,
                ex.getMessage()
        );
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Funcionalidad no implementada");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}

