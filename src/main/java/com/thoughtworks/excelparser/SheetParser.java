package com.thoughtworks.excelparser;

import com.thoughtworks.excelparser.annotations.ExcelField;
import com.thoughtworks.excelparser.annotations.ExcelObject;
import com.thoughtworks.excelparser.annotations.MappedExcelObject;
import com.thoughtworks.excelparser.annotations.ParseType;
import com.thoughtworks.excelparser.exception.ExcelParsingException;
import com.thoughtworks.excelparser.helper.HSSFHelper;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Sheet;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SheetParser {
    HSSFHelper hssfHelper;
    Map<String, Map<Integer, Field>> excelMapCache;

    public SheetParser() {
        hssfHelper = new HSSFHelper();
        excelMapCache = new HashMap<>();
    }

    public <T> List<T> createEntity(Sheet sheet, String sheetName, Class<T> clazz, Consumer<ExcelParsingException> errorHandler) {
        List<T> list = new ArrayList<>();
        ExcelObject excelObject = getExcelObject(clazz);
        for (int currentLocation = excelObject.start(); currentLocation <= excelObject.end(); currentLocation++) {
            T object = getNewInstance(sheet, sheetName, clazz, excelObject.parseType(), currentLocation, excelObject.zeroIfNull(), errorHandler);
            List<Field> mappedExcelFields = getMappedExcelObjects(clazz);
            for (Field mappedField : mappedExcelFields) {
                Class<?> fieldType = mappedField.getType();
                Class<?> clazz1 = fieldType.equals(List.class) ? getFieldType(mappedField) : fieldType;
                List<?> fieldValue = createEntity(sheet, sheetName, clazz1, errorHandler);
                if (fieldType.equals(List.class)) {
                    setFieldValue(mappedField, object, fieldValue);
                } else if (!fieldValue.isEmpty()) {
                    setFieldValue(mappedField, object, fieldValue.get(0));
                }
            }
            list.add(object);
        }
        return list;
    }

    public <T> List<T> createEntity(Sheet sheet, String sheetName, Class<T> clazz) throws ExcelParsingException {
        return createEntity(sheet, sheetName, clazz, error -> {
            throw error;
        });
    }

    private Class<?> getFieldType(Field field) {
        Type type = field.getGenericType();
        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            return (Class<?>) pt.getActualTypeArguments()[0];
        }

        return null;
    }

    private <T> List<Field> getMappedExcelObjects(Class<T> clazz) {
        List<Field> fieldList = new ArrayList<Field>();
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            MappedExcelObject mappedExcelObject = field.getAnnotation(MappedExcelObject.class);
            if (mappedExcelObject != null) {
                field.setAccessible(true);
                fieldList.add(field);
            }
        }
        return fieldList;
    }

    private <T> ExcelObject getExcelObject(Class<T> clazz)
            throws ExcelParsingException {

        ExcelObject excelObject = clazz.getAnnotation(ExcelObject.class);
        if (excelObject == null) {
            throw new ExcelParsingException("Invalid class configuration - ExcelObject annotation missing - " + clazz.getSimpleName());
        }
        return excelObject;
    }

    private <T> T getNewInstance(Sheet sheet, String sheetName, Class<T> clazz, ParseType parseType, Integer currentLocation, boolean zeroIfNull, Consumer<ExcelParsingException> errorHandler)
            throws ExcelParsingException {

        T object = getInstance(clazz);
        Map<Integer, Field> excelPositionMap = getExcelFieldPositionMap(clazz);
        for (Integer position : excelPositionMap.keySet()) {
            Field field = excelPositionMap.get(position);
            Object cellValue;
            if (ParseType.ROW == parseType) {
                cellValue = hssfHelper.getCellValue(sheet, sheetName, field.getType(), currentLocation, position,
                        zeroIfNull, errorHandler);
            } else {
                cellValue = hssfHelper.getCellValue(sheet, sheetName, field.getType(), position, currentLocation,
                        zeroIfNull, errorHandler);
            }
            setFieldValue(field, object, cellValue);
        }

        return object;
    }

    private <T> T getInstance(Class<T> clazz) throws ExcelParsingException {
        T object;
        try {
            Constructor<T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            object = constructor.newInstance();
        } catch (Exception e) {
            log.error("Exception occured while instantiating the class {}", clazz.getName(), e);
            throw new ExcelParsingException("Exception occured while instantiating the class " + clazz.getName(), e);
        }
        return object;
    }

    private <T> void setFieldValue(Field field, T object, Object cellValue) throws ExcelParsingException {
        try {
            field.set(object, cellValue);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            log.error(e.getMessage(), e);
            throw new ExcelParsingException("Exception occured while setting field value ", e);
        }
    }

    private <T> Map<Integer, Field> getExcelFieldPositionMap(Class<T> clazz) {
        Map<Integer, Field> existingMap = excelMapCache.get(clazz.getName());
        return existingMap == null ? loadCache(clazz) : existingMap;
    }

    /**
     * Load cached for the given class.
     *
     * @param clazz Class object to investigate.
     * @return Map.
     */
    private <T> Map<Integer, Field> loadCache(Class<T> clazz) {
        Map<Integer, Field> fieldMap = new HashMap<>();
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            ExcelField excelField = field.getAnnotation(ExcelField.class);
            if (excelField != null) {
                field.setAccessible(true);
                fieldMap.put(excelField.position(), field);
            }
        }
        excelMapCache.put(clazz.getName(), fieldMap);
        return fieldMap;
    }
}
