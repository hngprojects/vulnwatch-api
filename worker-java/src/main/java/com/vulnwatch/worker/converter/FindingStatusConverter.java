package com.vulnwatch.worker.converter;

import com.vulnwatch.worker.enums.FindingStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.stream.Stream;

@Converter(autoApply = true)
public class FindingStatusConverter implements AttributeConverter<FindingStatus, String> {

    @Override
    public String convertToDatabaseColumn(FindingStatus attribute) {
        return attribute != null ? attribute.getName() : null;
    }

    @Override
    public FindingStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        return Stream.of(FindingStatus.values())
                .filter(c -> c.getName().equalsIgnoreCase(dbData.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown FindingStatus DB value: " + dbData));
    }
}