package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Objects;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see TagObjectBuilder
 */
public class TagObject implements JsonWriter {

    public final String name;
    public final String description;
    public final ExternalDocumentationObject externalDocs;

    TagObject(String name, String description, ExternalDocumentationObject externalDocs) {
        notNull("name", name);
        this.name = name;
        this.description = description;
        this.externalDocs = externalDocs;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "name", name, isFirst);
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "externalDocs", externalDocs, isFirst);
        writer.write('}');
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TagObject tagObject = (TagObject) o;
        return name.equals(tagObject.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
