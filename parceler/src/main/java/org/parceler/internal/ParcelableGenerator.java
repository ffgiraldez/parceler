/**
 * Copyright 2013 John Ericksen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.parceler.internal;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import com.sun.codemodel.*;
import org.androidtransfuse.TransfuseAnalysisException;
import org.androidtransfuse.adapter.ASTConstructor;
import org.androidtransfuse.adapter.ASTParameter;
import org.androidtransfuse.adapter.ASTType;
import org.androidtransfuse.adapter.classes.ASTClassFactory;
import org.androidtransfuse.gen.ClassGenerationUtil;
import org.androidtransfuse.gen.ClassNamer;
import org.androidtransfuse.gen.InvocationBuilder;
import org.androidtransfuse.gen.UniqueVariableNamer;
import org.androidtransfuse.util.matcher.Matcher;
import org.androidtransfuse.util.matcher.Matchers;
import org.parceler.ParcelConverter;
import org.parceler.ParcelWrapper;
import org.parceler.ParcelerRuntimeException;
import org.parceler.Parcels;

import javax.inject.Inject;
import java.io.Serializable;
import java.util.*;


/**
 * @author John Ericksen
 */
public class ParcelableGenerator {

    private static final String CREATOR_CLASS_NAME = "CREATOR";
    private static final String CREATE_FROM_PARCEL = "createFromParcel";
    private static final String NEW_ARRAY = "newArray";
    private static final String WRITE_TO_PARCEL = "writeToParcel";
    private static final String DESCRIBE_CONTENTS = "describeContents";
    public static final String WRAP_METHOD = "wrap";

    private final JCodeModel codeModel;
    private final UniqueVariableNamer variableNamer;
    private final ClassNamer classNamer;
    private final ASTClassFactory astClassFactory;
    private final ClassGenerationUtil generationUtil;
    private final ExternalParcelRepository externalParcelRepository;
    private final ReadReferenceVisitor readFromParcelVisitor;
    private final WriteReferenceVisitor writeToParcelVisitor;
    private final InvocationBuilder invocationBuilder;

    private final Map<Matcher<ASTType>, ReadWriteGenerator> generators = new LinkedHashMap<Matcher<ASTType>, ReadWriteGenerator>();

    @Inject
    public ParcelableGenerator(JCodeModel codeModel,
                               UniqueVariableNamer variableNamer,
                               ClassNamer classNamer,
                               ASTClassFactory astClassFactory,
                               ClassGenerationUtil generationUtil,
                               ExternalParcelRepository externalParcelRepository,
                               ReadReferenceVisitor readFromParcelVisitor,
                               WriteReferenceVisitor writeToParcelVisitor,
                               InvocationBuilder invocationBuilder) {
        this.codeModel = codeModel;
        this.variableNamer = variableNamer;
        this.classNamer = classNamer;
        this.astClassFactory = astClassFactory;
        this.generationUtil = generationUtil;
        this.externalParcelRepository = externalParcelRepository;
        this.readFromParcelVisitor = readFromParcelVisitor;
        this.writeToParcelVisitor = writeToParcelVisitor;
        this.invocationBuilder = invocationBuilder;

        setup();
    }

    public JDefinedClass generateParcelable(ASTType type, ParcelableDescriptor parcelableDescriptor) {
        try {
            JType inputType = generationUtil.ref(type);

            JDefinedClass parcelableClass = generationUtil.defineClass(ClassNamer.className(type).append(Parcels.IMPL_EXT).build());
            parcelableClass._implements(Parcelable.class)
                    ._implements(codeModel.ref(ParcelWrapper.class).narrow(inputType));

            //wrapped @Parcel
            JFieldVar wrapped = parcelableClass.field(JMod.PRIVATE, inputType, variableNamer.generateName(type));

            //Parcel constructor
            JMethod parcelConstructor = parcelableClass.constructor(JMod.PUBLIC);
            JVar parcelParam = parcelConstructor.param(codeModel.ref(Parcel.class), variableNamer.generateName(Parcel.class));
            JBlock parcelConstructorBody = parcelConstructor.body();

            //writeToParcel(android.os.Parcel,int)
            JMethod writeToParcelMethod = parcelableClass.method(JMod.PUBLIC, codeModel.VOID, WRITE_TO_PARCEL);
            writeToParcelMethod.annotate(Override.class);
            JVar wtParcelParam = writeToParcelMethod.param(Parcel.class, variableNamer.generateName(Parcel.class));
            JVar flags = writeToParcelMethod.param(codeModel.INT, "flags");

            if (parcelableDescriptor.getParcelConverterType() == null) {

                //constructor
                if(parcelableDescriptor.getConstructorPair() == null){
                    JInvocation constructorInvocation = JExpr._new(inputType);
                    parcelConstructorBody.assign(wrapped, constructorInvocation);
                }
                else {
                    buildReadFromParcel(parcelableClass, parcelConstructorBody, type, wrapped, parcelableDescriptor.getConstructorPair(), parcelParam);
                    for(ASTParameter parameter : parcelableDescriptor.getConstructorPair().getConstructor().getParameters()){
                        AccessibleReference reference = parcelableDescriptor.getConstructorPair().getWriteReference(parameter);
                        buildWriteToParcel(writeToParcelMethod.body(), wtParcelParam, flags, reference, type, wrapped);
                    }
                }
                //field
                for (ReferencePair<FieldReference> fieldPair : parcelableDescriptor.getFieldPairs()) {
                    buildReadFromParcel(parcelableClass, parcelConstructorBody, wrapped, fieldPair.getSetter(), parcelParam);
                    buildWriteToParcel(writeToParcelMethod.body(), wtParcelParam, flags, fieldPair.getAccessor(), type, wrapped);
                }
                //method
                for (ReferencePair<MethodReference> methodPair : parcelableDescriptor.getMethodPairs()) {
                    buildReadFromParcel(parcelableClass, parcelConstructorBody, wrapped, methodPair.getSetter(), parcelParam);
                    buildWriteToParcel(writeToParcelMethod.body(), wtParcelParam, flags, methodPair.getAccessor(), type, wrapped);
                }
            } else {
                JClass converterType = generationUtil.ref(parcelableDescriptor.getParcelConverterType());
                JFieldVar converterField = parcelableClass.field(JMod.PRIVATE, converterType,
                        variableNamer.generateName(parcelableDescriptor.getParcelConverterType()), JExpr._new(converterType));

                parcelConstructorBody.assign(wrapped, JExpr.invoke(converterField, ParcelConverter.CONVERT_FROM_PARCEL).arg(parcelParam));

                writeToParcelMethod.body().invoke(converterField, ParcelConverter.CONVERT_TO_PARCEL).arg(wrapped).arg(wtParcelParam);
            }

            //@Parcel input
            JMethod inputConstructor = parcelableClass.constructor(JMod.PUBLIC);
            JVar inputParam = inputConstructor.param(inputType, variableNamer.generateName(type));
            inputConstructor.body().assign(wrapped, inputParam);

            //describeContents()
            JMethod describeContentsMethod = parcelableClass.method(JMod.PUBLIC, codeModel.INT, DESCRIBE_CONTENTS);
            describeContentsMethod.annotate(Override.class);
            describeContentsMethod.body()._return(JExpr.lit(0));

            //ParcelWrapper.getParcel()
            JMethod getWrappedMethod = parcelableClass.method(JMod.PUBLIC, inputType, ParcelWrapper.GET_PARCEL);
            getWrappedMethod.annotate(Override.class);
            getWrappedMethod.body()._return(wrapped);

            //public static final CREATOR = ...
            JDefinedClass creatorClass = parcelableClass._class(JMod.PRIVATE | JMod.STATIC | JMod.FINAL, classNamer.numberedClassName(Parcelable.Creator.class).build().getClassName());

            creatorClass._implements(codeModel.ref(Parcelable.Creator.class).narrow(parcelableClass));

            //createFromParcel method
            JMethod createFromParcelMethod = creatorClass.method(JMod.PUBLIC, parcelableClass, CREATE_FROM_PARCEL);
            createFromParcelMethod.annotate(Override.class);
            JVar cfpParcelParam = createFromParcelMethod.param(Parcel.class, variableNamer.generateName(Parcel.class));

            createFromParcelMethod.body()._return(JExpr._new(parcelableClass).arg(cfpParcelParam));

            //newArray method
            JMethod newArrayMethod = creatorClass.method(JMod.PUBLIC, parcelableClass.array(), NEW_ARRAY);
            newArrayMethod.annotate(Override.class);
            JVar sizeParam = newArrayMethod.param(codeModel.INT, "size");

            newArrayMethod.body()._return(JExpr.newArray(parcelableClass, sizeParam));

            //public static final Creator<type> CREATOR
            JFieldVar creatorField = parcelableClass.field(JMod.PUBLIC | JMod.STATIC | JMod.FINAL, creatorClass, CREATOR_CLASS_NAME, JExpr._new(creatorClass));
            creatorField.annotate(SuppressWarnings.class).param("value", "UnusedDeclaration");

            return parcelableClass;
        } catch (JClassAlreadyExistsException e) {
            throw new TransfuseAnalysisException("Class Already Exists: " + ClassNamer.className(type).append(Parcels.IMPL_EXT).build(), e);
        }
    }

    private void buildReadFromParcel(JDefinedClass parcelableClass, JBlock parcelConstructorBody, JFieldVar wrapped, MethodReference propertyAccessor, JVar parcelParam) {
        //invocation
        propertyAccessor.accept(readFromParcelVisitor,
                new ReadContext(parcelConstructorBody, wrapped, propertyAccessor.getType(), buildReadFromParcelExpression(parcelParam, parcelableClass, propertyAccessor.getType())));
    }

    private void buildReadFromParcel(JDefinedClass parcelableClass, JBlock parcelConstructorBody, JFieldVar wrapped, FieldReference propertyAccessor, JVar parcelParam) {
        //invocation
        propertyAccessor.accept(readFromParcelVisitor,
                new ReadContext(parcelConstructorBody, wrapped, propertyAccessor.getType(), buildReadFromParcelExpression(parcelParam, parcelableClass, propertyAccessor.getType())));
    }
    
    private void buildReadFromParcel(JDefinedClass parcelableClass, JBlock parcelConstructorBody,ASTType wrappedType,  JFieldVar wrapped, ConstructorReference propertyAccessor, JVar parcelParam){

        ASTConstructor constructor = propertyAccessor.getConstructor();
        List<ASTType> parameterTypes = new ArrayList<ASTType>();
        List<JExpression> inputExpression = new ArrayList<JExpression>();

        for (ASTParameter parameter : constructor.getParameters()) {
            parameterTypes.add(parameter.getASTType());
            inputExpression.add(buildReadFromParcelExpression(parcelParam, parcelableClass, parameter.getASTType()));
        }

        parcelConstructorBody.assign(wrapped, invocationBuilder.buildConstructorCall(constructor.getAccessModifier(), parameterTypes, inputExpression, wrappedType));
    }

    private JExpression buildReadFromParcelExpression(JVar parcelParam, JDefinedClass parcelableClass, ASTType type){
        JClass returnJClassRef = generationUtil.ref(type);

        ReadWriteGenerator generator = getGenerator(type);

        return generator.generateReader(parcelParam, type, returnJClassRef, parcelableClass);
    }

    private void buildWriteToParcel(JBlock body, JVar parcel, JVar flags, AccessibleReference reference, ASTType wrappedType, JFieldVar wrapped) {
        ASTType type = reference.getType();
        JExpression getExpression = reference.accept(writeToParcelVisitor, new WriteContext(wrapped, wrappedType));

        ReadWriteGenerator generator = getGenerator(type);

        generator.generateWriter(body, parcel, flags, type, getExpression);
    }

    private ReadWriteGenerator getGenerator(ASTType type) {
        for (Map.Entry<Matcher<ASTType>, ReadWriteGenerator> generatorEntry : generators.entrySet()) {
            if(generatorEntry.getKey().matches(type)){
                return generatorEntry.getValue();
            }
        }
        throw new ParcelerRuntimeException("Unable to find appropriate Parcel method to write " + type.getName());
    }

    public interface ReadWriteGenerator{

        JExpression generateReader(JVar parcelParam, ASTType type, JClass returnJClassRef, JDefinedClass parcelableClass);

        void generateWriter(JBlock body, JVar parcel, JVar flags, ASTType type, JExpression getExpression);
    }

    public static class SimpleReadWriteGenerator extends ReadWriteGeneratorBase{

        public SimpleReadWriteGenerator(String readMethod, String writeMethod) {
            super(readMethod, writeMethod);
        }

        public SimpleReadWriteGenerator(String readMethod, Class[] readMethodParams, String writeMethod, Class[] writeMethodParams) {
            super(readMethod, readMethodParams, writeMethod, writeMethodParams);
        }

        @Override
        public JExpression generateReader(JVar parcelParam, ASTType type, JClass returnJClassRef, JDefinedClass parcelableClass) {
            return parcelParam.invoke(getReadMethod());
        }

        @Override
        public void generateWriter(JBlock body, JVar parcel, JVar flags, ASTType type, JExpression getExpression) {
            body.invoke(parcel, getWriteMethod()).arg(getExpression);
        }
    }

    public static class ClassloaderReadWriteGenerator extends ReadWriteGeneratorBase {

        public ClassloaderReadWriteGenerator(String readMethod, String writeMethod, Class writeMethodType) {
            super(readMethod, new Class[]{ClassLoader.class}, writeMethod, new Class[]{writeMethodType});
        }

        @Override
        public JExpression generateReader(JVar parcelParam, ASTType type, JClass returnJClassRef, JDefinedClass parcelableClass) {
            return parcelParam.invoke(getReadMethod()).arg(returnJClassRef.dotclass().invoke("getClassLoader"));
        }

        @Override
        public void generateWriter(JBlock body, JVar parcel, JVar flags, ASTType type, JExpression getExpression) {
            body.invoke(parcel, getWriteMethod()).arg(getExpression);
        }
    }

    public static abstract class ReadWriteGeneratorBase implements ReadWriteGenerator{
        private final String readMethod;
        private final Class[] readMethodParams;
        private final String writeMethod;
        private final Class[] writeMethodParams;

        public ReadWriteGeneratorBase(String readMethod, Class[] readMethodParams, String writeMethod, Class[] writeMethodParams) {
            this.readMethod = readMethod;
            this.readMethodParams = readMethodParams;
            this.writeMethod = writeMethod;
            this.writeMethodParams = writeMethodParams;
        }

        public ReadWriteGeneratorBase(String readMethod, String writeMethod) {
            this(readMethod, new Class[0], writeMethod, new Class[0]);
        }

        public String getReadMethod() {
            return readMethod;
        }

        public Class[] getReadMethodParams() {
            return readMethodParams;
        }

        public String getWriteMethod() {
            return writeMethod;
        }

        public Class[] getWriteMethodParams() {
            return writeMethodParams;
        }
    }

    public static class ParcelableReadWriteGenerator extends ReadWriteGeneratorBase {

        public ParcelableReadWriteGenerator(String readMethod, String writeMethod, Class parcelableType) {
            super(readMethod, new Class[]{ClassLoader.class}, writeMethod, new Class[]{parcelableType, int.class});
        }

        @Override
        public JExpression generateReader(JVar parcelParam, ASTType type, JClass returnJClassRef, JDefinedClass parcelableClass) {
            return JExpr.cast(returnJClassRef, parcelParam.invoke(getReadMethod()).arg(returnJClassRef.dotclass().invoke("getClassLoader")));
        }

        @Override
        public void generateWriter(JBlock body, JVar parcel, JVar flags, ASTType type, JExpression getExpression) {
            body.invoke(parcel, getWriteMethod()).arg(getExpression).arg(flags);
        }
    }

    public static class SerializableReadWriteGenerator extends ReadWriteGeneratorBase {

        public SerializableReadWriteGenerator() {
            super("readSerializable", new Class[0], "writeSerializable", new Class[]{Serializable.class});
        }

        @Override
        public JExpression generateReader(JVar parcelParam, ASTType type, JClass returnJClassRef, JDefinedClass parcelableClass) {
            return JExpr.cast(returnJClassRef, parcelParam.invoke(getReadMethod()));
        }

        @Override
        public void generateWriter(JBlock body, JVar parcel, JVar flags, ASTType type, JExpression getExpression) {
            body.invoke(parcel, getWriteMethod()).arg(getExpression);
        }
    }

    public static class ParcelReadWriteGenerator extends ReadWriteGeneratorBase {

        private final ClassGenerationUtil generationUtil;
        private final JCodeModel codeModel;

        public ParcelReadWriteGenerator(ClassGenerationUtil generationUtil, JCodeModel codeModel) {
            super("readParcelable", new Class[]{ClassLoader.class}, "writeParcelable", new Class[]{Parcelable.class, int.class});
            this.generationUtil = generationUtil;
            this.codeModel = codeModel;
        }

        @Override
        public JExpression generateReader(JVar parcelParam, ASTType type, JClass returnJClassRef, JDefinedClass parcelableClass) {
            JClass wrapperRef = codeModel.ref(ParcelWrapper.class).narrow(generationUtil.ref(type));
            return ((JExpression) JExpr.cast(wrapperRef, parcelParam.invoke(getReadMethod())
                    .arg(parcelableClass.dotclass().invoke("getClassLoader")))).invoke(ParcelWrapper.GET_PARCEL);
        }

        @Override
        public void generateWriter(JBlock body, JVar parcel, JVar flags, ASTType type, JExpression getExpression) {
            JInvocation wrappedParcel = generationUtil.ref(ParcelsGenerator.PARCELS_NAME).staticInvoke(WRAP_METHOD).arg(getExpression);
            body.invoke(parcel, getWriteMethod()).arg(wrappedParcel).arg(flags);
        }
    }

    public static class ImplementsMatcher implements Matcher<ASTType>{

        private final ASTType superType;

        public ImplementsMatcher(ASTType superType) {
            this.superType = superType;
        }

        @Override
        public boolean matches(ASTType astType) {
            return astType.implementsFrom(superType);
        }
    }

    public static class InheritsMatcher implements Matcher<ASTType>{

        private final ASTType superType;

        public InheritsMatcher(ASTType superType) {
            this.superType = superType;
        }

        @Override
        public boolean matches(ASTType astType) {
            return astType.inheritsFrom(superType);
        }
    }

    public static class ParcelMatcher implements Matcher<ASTType>{

        private final ExternalParcelRepository externalParcelRepository;

        public ParcelMatcher(ExternalParcelRepository externalParcelRepository) {
            this.externalParcelRepository = externalParcelRepository;
        }

        @Override
        public boolean matches(ASTType type) {
            return type.isAnnotated(org.parceler.Parcel.class) || externalParcelRepository.contains(type);
        }
    }

    private void setup() {
        addPair(byte.class, "readByte", "writeByte");
        addPair(Byte.class, "readByte", "writeByte", byte.class);
        addPair(double.class, "readDouble", "writeDouble");
        addPair(Double.class, "readDouble", "writeDouble", double.class);
        addPair(float.class, "readFloat", "writeFloat");
        addPair(Float.class, "readFloat", "writeFloat", float.class);
        addPair(int.class, "readInt", "writeInt");
        addPair(Integer.class, "readInt", "writeInt", int.class);
        addPair(long.class, "readLong", "writeLong");
        addPair(Long.class, "readLong", "writeLong", long.class);
        addPair(byte[].class, "createByteArray", "writeByteArray");
        addPair(char[].class, "createCharArray", "writeCharArray");
        addPair(boolean[].class, "createBooleanArray", "writeBooleanArray");
        addPair(int[].class, "createIntArray", "writeIntArray");
        addPair(long[].class, "createLongArray", "writeLongArray");
        addPair(float[].class, "createFloatArray", "writeFloatArray");
        addPair(double[].class, "createDoubleArray", "writeDoubleArray");
        addPair(String[].class, "createStringArray", "writeStringArray");
        addPair(String.class, "readString", "writeString");
        addPair(IBinder.class, "readStrongBinder", "writeStrongBinder");
        addPair(Bundle.class, "readBundle", "writeBundle");
        generators.put(Matchers.type(astClassFactory.getType(Object[].class)).build(), new ClassloaderReadWriteGenerator("readArray", "writeArray", Object[].class));
        addPair(Exception.class, "readException", "writeException");
        addPair(SparseBooleanArray.class, "readSparseBooleanArray", "writeSparseBooleanArray");
        generators.put(Matchers.type(astClassFactory.getType(SparseArray.class)).ignoreGenerics().build(), new ClassloaderReadWriteGenerator("readSparseArray", "writeSparseArray", SparseArray.class));
        generators.put(new ImplementsMatcher(astClassFactory.getType(Parcel.class)), new ParcelableReadWriteGenerator("readParcelable", "writeParcelable", Parcelable.class));
        generators.put(new ImplementsMatcher(astClassFactory.getType(Parcel[].class)), new ParcelableReadWriteGenerator("readParcelableArray", "writeParcelableArray", Parcelable[].class));
        generators.put(new ParcelMatcher(externalParcelRepository), new ParcelReadWriteGenerator(generationUtil, codeModel));
        generators.put(Matchers.type(astClassFactory.getType(List.class)).ignoreGenerics().build(), new ClassloaderReadWriteGenerator("readArrayList", "writeList", List.class));
        generators.put(Matchers.type(astClassFactory.getType(ArrayList.class)).ignoreGenerics().build(), new ClassloaderReadWriteGenerator("readArrayList", "writeList", List.class));
        generators.put(Matchers.type(astClassFactory.getType(Map.class)).ignoreGenerics().build(), new ClassloaderReadWriteGenerator("readHashMap", "writeMap", Map.class));
        generators.put(Matchers.type(astClassFactory.getType(HashMap.class)).ignoreGenerics().build(), new ClassloaderReadWriteGenerator("readHashMap", "writeMap", Map.class));
        generators.put(new InheritsMatcher(astClassFactory.getType(Serializable.class)), new SerializableReadWriteGenerator());
    }

    private void addPair(Class clazz, String readMethod, String writeMethod) {
        addPair(clazz, readMethod, writeMethod, clazz);
    }

    private void addPair(Class clazz, String readMethod, String writeMethod, Class writeParam) {
        generators.put(Matchers.type(astClassFactory.getType(clazz)).build(), new SimpleReadWriteGenerator(readMethod, new Class[0], writeMethod, new Class[]{writeParam}));
    }

    protected Map<Matcher<ASTType>, ReadWriteGenerator> getGenerators() {
        return generators;
    }

    public static void main(String[] args){
        System.out.println(double[].class.getName());
    }
}
