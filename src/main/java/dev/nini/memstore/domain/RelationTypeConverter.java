package dev.nini.memstore.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Maps {@link RelationType} to/from its lowercase database token. */
@Converter(autoApply = true)
public class RelationTypeConverter implements AttributeConverter<RelationType, String> {

    @Override
    public String convertToDatabaseColumn(RelationType attribute) {
        return attribute == null ? null : attribute.code();
    }

    @Override
    public RelationType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : RelationType.fromCode(dbData);
    }
}
