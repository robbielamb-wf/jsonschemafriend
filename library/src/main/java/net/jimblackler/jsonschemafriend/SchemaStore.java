package net.jimblackler.jsonschemafriend;

import static net.jimblackler.jsonschemafriend.CacheLoader.load;
import static net.jimblackler.jsonschemafriend.DocumentUtils.parseJson;
import static net.jimblackler.jsonschemafriend.MetaSchemaDetector.detectMetaSchema;
import static net.jimblackler.jsonschemafriend.PathUtils.append;
import static net.jimblackler.jsonschemafriend.PathUtils.baseDocumentFromUri;
import static net.jimblackler.jsonschemafriend.PathUtils.fixUnescaped;
import static net.jimblackler.jsonschemafriend.PathUtils.normalize;
import static net.jimblackler.jsonschemafriend.PathUtils.resolve;
import static net.jimblackler.jsonschemafriend.Validator.validate;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SchemaStore {
  private static final Logger LOG = Logger.getLogger(SchemaStore.class.getName());

  private static final URI ROOT = URI.create("");

  private final Map<URI, Object> canonicalUriToObject = new HashMap<>();
  private final Map<URI, Object> canonicalUriToBaseObject = new HashMap<>();
  private final Map<URI, URI> validUriToCanonicalUri = new HashMap<>();
  private final Map<URI, URI> canonicalUriToResourceApi = new HashMap<>();
  private final Map<URI, Schema> builtSchemas = new HashMap<>();
  private final Collection<URI> mapped = new HashSet<>();
  private final UrlRewriter urlRewriter;
  private int memorySchemaNumber;
  private final RegExPatternSupplier regExPatternSupplier;

  public SchemaStore() {
    urlRewriter = null;
    regExPatternSupplier = Ecma262Pattern::new;
  }

  public SchemaStore(RegExPatternSupplier regExPatternSupplier, UrlRewriter urlRewriter) {
    this.regExPatternSupplier = regExPatternSupplier;
    this.urlRewriter = urlRewriter;
  }

  public SchemaStore(UrlRewriter urlRewriter) {
    regExPatternSupplier = Ecma262Pattern::new;
    this.urlRewriter = urlRewriter;
  }

  public Schema loadSchema(Object object) throws GenerationException {
    // Every document needs a default canonical URI. We generate a nice short one. If the client
    // needs it, it can be obtained via schema.getUri();

    URI canonicalUri = store(URI.create("ram://" + memorySchemaNumber++), object);
    return loadSchema(canonicalUri);
  }

  public Schema loadSchema(File file) throws GenerationException {
    return loadSchema(file.toURI());
  }

  public Schema loadSchema(URL url) throws GenerationException {
    try {
      return loadSchema(url.toURI());
    } catch (URISyntaxException e) {
      throw new GenerationException(e);
    }
  }

  public Schema loadSchema(URI uri) throws GenerationException {
    return loadSchema(uri, true);
  }

  public Schema loadSchema(URI uri, boolean prevalidate) throws GenerationException {
    uri = normalize(uri);
    while (true) {
      if (builtSchemas.containsKey(uri)) {
        return builtSchemas.get(uri);
      }

      if (validUriToCanonicalUri.containsKey(uri)) {
        uri = validUriToCanonicalUri.get(uri);
        continue;
      }
      if (!canonicalUriToObject.containsKey(uri)) {
        // We don't know this canonical URL, so we treat it as a resource URL and try to fetch
        // it.
        URI documentUri = baseDocumentFromUri(uri);
        LOG.fine("Loading: " + documentUri + " to resolve: " + uri);

        try {
          String content;
          if (urlRewriter == null) {
            content = load(documentUri);
          } else {
            content = load(urlRewriter.rewrite(documentUri));
          }
          if (!mapped.contains(documentUri)) {
            store(documentUri, parseJson(content));
            continue;
          }
        } catch (JSONException | IOException e) {
          LOG.warning("Failed attempt to auto fetch to resolve: " + uri);
        }
      }

      Object schemaObject = getObject(uri);
      if (!(schemaObject instanceof JSONObject)) {
        break;
      }
      JSONObject schemaJsonObject = (JSONObject) schemaObject;
      Object refObject = schemaJsonObject.opt("$ref");
      if (refObject instanceof String) {
        String refString = fixUnescaped((String) refObject);
        URI pointingTo = resolve(uri, URI.create(refString));
        URI metaSchema = detectMetaSchema(canonicalUriToBaseObject.get(uri));
        if (metaSchema.equals(MetaSchemaUris.DRAFT_2019_09)) {
          if (schemaJsonObject.length() > 1) {
            break;
          }
        }
        uri = pointingTo;
      } else {
        break;
      }
    }
    // If we can, we fetch and build the schema's meta-schema and validate the object against it,
    // before we attempt to build the Schema.
    // This can't be done inside the Schema builder because the schema's meta-schema might be in its
    // own graph, so the meta-schema won't be built in full when it's first available.

    if (prevalidate) {
      Object schemaObject = getObject(uri);
      if (schemaObject != null) {
        URI metaSchemaUri = detectMetaSchema(canonicalUriToBaseObject.get(uri));
        if (!normalize(metaSchemaUri).equals(uri)) {
          Schema metaSchema = loadSchema(metaSchemaUri, false);
          List<ValidationError> errors = new ArrayList<>();
          validate(metaSchema, schemaObject, ROOT,
              validationError -> !(validationError instanceof FormatError), errors::add);
          if (!errors.isEmpty()) {
            throw new GenerationException(errors.stream()
                                              .map(Object::toString)
                                              .collect(Collectors.joining(System.lineSeparator())));
          }
        }
      }
    }

    return new Schema(this, uri, this.regExPatternSupplier);
  }

  public void register(URI path, Schema schema) throws GenerationException {
    if (builtSchemas.put(path, schema) != null) {
      throw new GenerationException(path + " already registered");
    }
  }

  public URI store(URI uri, Object object) {
    if (!mapped.add(uri)) {
      throw new IllegalStateException("Double mapped");
    }
    return map(object, object, uri, uri, detectMetaSchema(object), true);
  }

  URI map(Object object, Object baseObject, URI validUri, URI canonicalBaseUri, URI metaSchema,
      boolean isResource) {
    String idKey =
        (MetaSchemaUris.DRAFT_3.equals(metaSchema) || MetaSchemaUris.DRAFT_4.equals(metaSchema))
        ? "id"
        : "$id";
    URI canonicalUri = canonicalBaseUri;
    if (object instanceof JSONObject) {
      JSONObject jsonObject = (JSONObject) object;
      Object idObject = jsonObject.opt(idKey);
      if (idObject instanceof String) {
        URI child = URI.create((String) idObject);
        if (MetaSchemaUris.DRAFT_2019_09.equals(metaSchema) && child.getRawFragment() != null
            && !child.getRawFragment().isEmpty()) {
          LOG.warning("Illegal fragment in ID");
        } else {
          canonicalUri = resolve(canonicalUri, child);
        }
      }
      Object anchorObject = jsonObject.opt("$anchor");
      if (anchorObject instanceof String) {
        try {
          URI anchor = new URI(null, null, null, (String) anchorObject);
          canonicalUri = resolve(canonicalUri, anchor);
        } catch (URISyntaxException e) {
          LOG.warning("Problem with $anchor: " + e.getMessage());
        }
      }
    }

    if (isResource) {
      URI was = canonicalUriToResourceApi.put(canonicalUri, validUri);
      if (was != null) {
        LOG.warning("Attempt to map from at least two locations: " + canonicalUri
            + System.lineSeparator() + validUri + System.lineSeparator() + was);
        return canonicalUri;
      }
      if (canonicalUriToObject.put(canonicalUri, object) != null) {
        throw new IllegalStateException(
            "Different content with same IDs found mapping " + canonicalUri);
      }

      if (canonicalUriToBaseObject.put(canonicalUri, baseObject) != null) {
        throw new IllegalStateException(
            "Different content with same IDs found mapping " + canonicalUri);
      }
    }
    if (!canonicalBaseUri.equals(canonicalUri)) {
      validUriToCanonicalUri.put(canonicalBaseUri, canonicalUri);
    }
    if (!validUri.equals(canonicalUri)) {
      validUriToCanonicalUri.put(validUri, canonicalUri);
    }

    if (object instanceof JSONObject) {
      JSONObject jsonObject = (JSONObject) object;
      for (String key : jsonObject.keySet()) {
        map(jsonObject.get(key), baseObject, append(validUri, key), append(canonicalUri, key),
            metaSchema, isResource);
        if (!canonicalBaseUri.equals(canonicalUri) && !canonicalBaseUri.equals(validUri)) {
          map(jsonObject.get(key), baseObject, append(canonicalBaseUri, key),
              append(canonicalUri, key), metaSchema, false);
        }
      }
    } else if (object instanceof JSONArray) {
      JSONArray jsonArray = (JSONArray) object;
      for (int idx = 0; idx != jsonArray.length(); idx++) {
        map(jsonArray.get(idx), baseObject, append(validUri, String.valueOf(idx)),
            append(canonicalUri, String.valueOf(idx)), metaSchema, isResource);
      }
    }

    return canonicalUri;
  }

  Object getObject(URI canonicalUri) {
    if (validUriToCanonicalUri.containsKey(canonicalUri)) {
      throw new IllegalStateException("getObject(): non-canonical URL received");
    }
    if (canonicalUriToObject.containsKey(canonicalUri)) {
      return canonicalUriToObject.get(canonicalUri);
    }
    return null;
  }
}
