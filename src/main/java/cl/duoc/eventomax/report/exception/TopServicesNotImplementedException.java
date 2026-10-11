package cl.duoc.eventomax.report.exception;

public class TopServicesNotImplementedException extends RuntimeException {
    public TopServicesNotImplementedException() {
        super("El reporte de top-services está diferido: requiere serviceId y datos reales de contratación de servicios no disponibles en el contrato actual.");
    }
}

