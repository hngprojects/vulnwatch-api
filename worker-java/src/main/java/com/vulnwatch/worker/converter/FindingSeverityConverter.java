package com.vulnwatch.worker.converter;

import com.vulnwatch.worker.enums.FindingSeverity;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.stream.Stream;

@Converter(autoApply = true)
public class FindingSeverityConverter implements AttributeConverter<FindingSeverity, String> {

    @Override
    public String convertToDatabaseColumn(FindingSeverity attribute) {
        return attribute != null ? attribute.getName() : null;
    }

    @Override
    public FindingSeverity convertToEntityAttribute(String dbData) {
        if (dbData == null)
            return null;
        return Stream.of(FindingSeverity.values())
                .filter(c -> c.getName().equalsIgnoreCase(dbData.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown FindingSeverity DB value: " + dbData));
    }
}