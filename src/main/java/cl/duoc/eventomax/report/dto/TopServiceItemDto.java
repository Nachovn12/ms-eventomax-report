package cl.duoc.eventomax.report.dto;

public record TopServiceItemDto(
        Long serviceId,
        String serviceName,
        long hireCount
) {}
