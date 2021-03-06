package org.parceler.internal;

import org.androidtransfuse.adapter.ASTField;
import org.androidtransfuse.adapter.ASTType;

/**
 * @author John Ericksen
 */
public class FieldReference implements AccessibleReference {

    private final String name;
    private final ASTField field;

    public FieldReference(String name, ASTField field) {
        this.field = field;
        this.name = name;
    }

    public ASTField getField() {
        return field;
    }

    public <T, R> R accept(ReferenceVisitor<T, R> visitor, T input){
        return visitor.visit(this, input);
    }

    public ASTType getType() {
        return field.getASTType();
    }

    public String getName() {
        return name;
    }
}
