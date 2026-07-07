package dev.nini.memstore.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Maps {@link FactStatus} to/from its lowercase database token. */
@Converter(autoApply = true)
public class FactStatusConverter implements AttributeConverter<FactStatus, String> {

    @Override
    public String convertToDatabaseColumn(FactStatus attribute) {
        return attribute == null ? null : attribute.code();
    }

    @Override
    public FactStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : FactStatus.fromCode(dbData);
    }
}
