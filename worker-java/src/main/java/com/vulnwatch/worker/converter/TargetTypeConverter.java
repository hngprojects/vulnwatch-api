package com.vulnwatch.worker.converter;

import com.vulnwatch.worker.enums.TargetType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.stream.Stream;

@Converter(autoApply = true)
public class TargetTypeConverter implements AttributeConverter<TargetType, String> {

    @Override
    public String convertToDatabaseColumn(TargetType attribute) {
        return attribute != null ? attribute.getName() : null;
    }

    @Override
    public TargetType convertToEntityAttribute(String dbData) {
        if (dbData == null)
            return null;
        return Stream.of(TargetType.values())
                .filter(c -> c.getName().equalsIgnoreCase(dbData.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown TargetType DB value: " + dbData));
    }
}