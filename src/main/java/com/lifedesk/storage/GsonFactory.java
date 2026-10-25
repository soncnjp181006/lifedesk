package com.lifedesk.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/** Gson instance shared by repositories, with java.time support. */
public final class GsonFactory {
    private GsonFactory() {
    }

    public static Gson create() {
        return new GsonBuilder()
                .disableHtmlEscaping()
                .setPrettyPrinting()
                .registerTypeAdapter(LocalDate.class, new LocalDateAdapter())
                .registerTypeAdapter(LocalTime.class, new LocalTimeAdapter())
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
                .registerTypeAdapter(Instant.class, new InstantAdapter())
                .registerTypeAdapter(YearMonth.class, new YearMonthAdapter())
                .create();
    }

    private static RuntimeException bad(String value, Type type) {
        return new com.lifedesk.common.StorageException("Giá trị ngày/giờ không hợp lệ: " + value + " (" + type + ")");
    }

    static final class LocalDateAdapter implements JsonSerializer<LocalDate>, JsonDeserializer<LocalDate> {
        @Override
        public JsonElement serialize(LocalDate src, Type type, JsonSerializationContext ctx) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public LocalDate deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) {
            try {
                return LocalDate.parse(json.getAsString());
            } catch (DateTimeParseException e) {
                throw bad(json.getAsString(), type);
            }
        }
    }

    static final class LocalTimeAdapter implements JsonSerializer<LocalTime>, JsonDeserializer<LocalTime> {
        @Override
        public JsonElement serialize(LocalTime src, Type type, JsonSerializationContext ctx) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public LocalTime deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) {
            try {
                return LocalTime.parse(json.getAsString());
            } catch (DateTimeParseException e) {
                throw bad(json.getAsString(), type);
            }
        }
    }

    static final class LocalDateTimeAdapter implements JsonSerializer<LocalDateTime>, JsonDeserializer<LocalDateTime> {
        @Override
        public JsonElement serialize(LocalDateTime src, Type type, JsonSerializationContext ctx) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public LocalDateTime deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) {
            try {
                return LocalDateTime.parse(json.getAsString());
            } catch (DateTimeParseException e) {
                throw bad(json.getAsString(), type);
            }
        }
    }

    static final class InstantAdapter implements JsonSerializer<Instant>, JsonDeserializer<Instant> {
        @Override
        public JsonElement serialize(Instant src, Type type, JsonSerializationContext ctx) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public Instant deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) {
            try {
                return Instant.parse(json.getAsString());
            } catch (DateTimeParseException e) {
                throw bad(json.getAsString(), type);
            }
        }
    }

    static final class YearMonthAdapter implements JsonSerializer<YearMonth>, JsonDeserializer<YearMonth> {
        @Override
        public JsonElement serialize(YearMonth src, Type type, JsonSerializationContext ctx) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public YearMonth deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) {
            try {
                return YearMonth.parse(json.getAsString());
            } catch (DateTimeParseException e) {
                throw bad(json.getAsString(), type);
            }
        }
    }
}
