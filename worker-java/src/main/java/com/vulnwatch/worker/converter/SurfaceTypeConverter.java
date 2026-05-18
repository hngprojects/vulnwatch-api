package com.vulnwatch.worker.entity.converter;

import com.vulnwatch.worker.enums.SurfaceType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.stream.Stream;

@Converter(autoApply = true)
public class SurfaceTypeConverter implements AttributeConverter<SurfaceType, String> {

    @Override
    public String convertToDatabaseColumn(SurfaceType attribute) {
        return attribute != null ? attribute.getName() : null;
    }

    @Override
    public SurfaceType convertToEntityAttribute(String dbData) {
        if (dbData == null)
            return null;
        return Stream.of(SurfaceType.values())
                .filter(c -> c.getName().equalsIgnoreCase(dbData.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown SurfaceType DB value: " + dbData));
    }
}