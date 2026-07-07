package dev.nini.memstore.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Maps {@link FactType} to/from its lowercase database token. */
@Converter(autoApply = true)
public class FactTypeConverter implements AttributeConverter<FactType, String> {

    @Override
    public String convertToDatabaseColumn(FactType attribute) {
        return attribute == null ? null : attribute.code();
    }

    @Override
    public FactType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : FactType.fromCode(dbData);
    }
}
