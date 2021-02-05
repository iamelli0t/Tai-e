/*
 * Tai-e: A Program Analysis Framework for Java
 *
 * Copyright (C) 2020 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020 Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * This software is designed for the "Static Program Analysis" course at
 * Nanjing University, and it supports a subset of Java features.
 * Tai-e is only for educational and academic purposes, and any form of
 * commercial use is disallowed.
 */

package pascal.taie.java.types;

import java.util.Objects;

public class ArrayType implements ReferenceType {

    private final Type baseType;
    private final int dimensions;
    private final Type elementType;

    public ArrayType(Type baseType, int dimensions, Type elementType) {
        this.baseType = baseType;
        this.dimensions = dimensions;
        this.elementType = elementType;
    }

    public Type getBaseType() {
        return baseType;
    }

    public int getDimensions() {
        return dimensions;
    }

    public Type getElementType() {
        return elementType;
    }

    @Override
    public String getName() {
        return elementType + "[]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ArrayType arrayType = (ArrayType) o;
        return dimensions == arrayType.dimensions
                && baseType.equals(arrayType.baseType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseType, dimensions);
    }

    @Override
    public String toString() {
        return getName();
    }
}