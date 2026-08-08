package dev.advancedtrains.atcs.web;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

final class OptionalTypeAdapterFactory implements TypeAdapterFactory {
    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        if (type.getRawType() != Optional.class) {
            return null;
        }
        Type wrappedType = ((ParameterizedType) type.getType()).getActualTypeArguments()[0];
        TypeAdapter<?> wrappedAdapter = gson.getAdapter(TypeToken.get(wrappedType));
        @SuppressWarnings({"unchecked", "rawtypes"})
        TypeAdapter<T> adapter = (TypeAdapter<T>) new OptionalAdapter(wrappedAdapter);
        return adapter;
    }

    private static final class OptionalAdapter<E> extends TypeAdapter<Optional<E>> {
        private final TypeAdapter<E> delegate;

        private OptionalAdapter(TypeAdapter<E> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(JsonWriter output, Optional<E> value) throws IOException {
            if (value == null || value.isEmpty()) {
                output.nullValue();
            } else {
                delegate.write(output, value.orElseThrow());
            }
        }

        @Override
        public Optional<E> read(JsonReader input) throws IOException {
            if (input.peek() == JsonToken.NULL) {
                input.nextNull();
                return Optional.empty();
            }
            return Optional.ofNullable(delegate.read(input));
        }
    }
}
