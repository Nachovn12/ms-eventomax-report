package cl.duoc.eventomax.report.exception;

public class InvalidReportRangeException extends RuntimeException {
    private final String invalidValue;

    public InvalidReportRangeException(String invalidValue) {
        super("El valor del parámetro 'range' no es válido: " + invalidValue);
        this.invalidValue = invalidValue;
    }

    public String getInvalidValue() {
        return invalidValue;
    }
}

