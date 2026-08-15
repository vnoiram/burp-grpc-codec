# Burp gRPC Codec

Burp gRPC Codec is a Burp Suite extension for inspecting and editing gRPC,
gRPC-Web, and schema-less Protocol Buffers payloads directly in HTTP message
editors.

The workflow is similar to PacketProxy's protobuf-friendly editing model:
binary protobuf data is decoded into editable JSON, and modified JSON is packed
back into the original wire format when Burp sends the message.

## Features

- Adds `gRPC Codec` tabs to Burp request and response editors.
- Detects gRPC framed payloads (`application/grpc`).
- Detects gRPC-Web binary payloads (`application/grpc-web`).
- Detects gRPC-Web text payloads (`application/grpc-web-text`) with base64
  transport encoding.
- Falls back to raw protobuf parsing for HTTP bodies that look like protobuf.
- Configures raw protobuf detection as broad, strict, or disabled.
- Loads optional schema metadata from local `.proto` files, compiled binary
  `FileDescriptorSet` files (`.protoset`/`.pb`/`.desc`/`.bin`, e.g. from
  `protoc --descriptor_set_out`), or gRPC Server Reflection.
- Decodes protobuf without `.proto` files using wire-format inference.
- Adds schema metadata such as field names and proto types while keeping the
  editable `f<number>` JSON format.
- Selects request and response message types from gRPC service/method paths
  when service definitions are available.
- Decodes and re-encodes packed repeated scalar fields when schema metadata is
  available.
- Resolves protobuf `enum` values to their symbolic name and accepts either
  the name or the number back when editing.
- Marks fields that belong to a `oneof` and fields that are protobuf `map<K, V>`
  entries in the decoded JSON.
- Adds a read-only convenience view for `google.protobuf.Any` (resolves the
  embedded message by `type_url` against the schema registry),
  `google.protobuf.Timestamp` / `Duration` (ISO-8601 / seconds), the
  well-known wrapper types (`StringValue`, `Int32Value`, `BoolValue`, etc.),
  `google.protobuf.FieldMask` (comma-joined paths), and
  `google.protobuf.Struct` / `Value` / `ListValue` (converted to equivalent
  plain JSON). The raw fields remain authoritative for re-encoding.
- Accepts schema field names in place of `f<number>` keys when editing JSON,
  as long as a schema is available.
- Decompresses and recompresses gzip- or deflate-compressed gRPC messages when
  `grpc-encoding: gzip` or `grpc-encoding: deflate` is present.
- Preserves repeated fields and unknown numeric field numbers.
- Re-encodes edited JSON back into protobuf and restores the original transport
  envelope.
- Adds a "Log decoded gRPC/protobuf body" context menu item that prints
  decoded JSON to the extension output for the selected message(s), without
  needing to open the editor tab.
- Adds a "Copy decoded gRPC/protobuf body to clipboard" context menu item.
- Adds a "Send decoded gRPC/protobuf bodies to Comparer" context menu item,
  useful for diffing two decoded messages.
- Optionally highlights and annotates Proxy history responses detected as
  gRPC/protobuf (off by default; see Settings).
- Surfaces `grpc-status` / `grpc-message` headers (common on grpc-web
  trailers-only responses) as read-only `grpcStatus` / `grpcStatusName` /
  `grpcMessage` fields in the decoded JSON, with standard gRPC status codes
  mapped to their name.
- Passively records observed gRPC service/method paths (host + `/pkg.Service/
  Method`) from declared gRPC/grpc-web requests, with a "gRPC Methods" suite
  tab listing them for recon, independent of Server Reflection.
- Accepts URL-safe and/or unpadded base64 in addition to standard base64 when
  decoding `grpc-web-text` bodies.
- Skips the schema-less raw-detection heuristic for bodies larger than a
  configurable size (default 1 MiB), to bound its cost on large responses.
- Adds a "Copy as grpcurl command" context menu item for gRPC/grpc-web
  requests, producing a runnable `grpcurl` invocation (`-plaintext` flag,
  target `host:port`, `pkg.Service/Method` path, and a best-effort plain-JSON
  `-d` payload from the decoded body) for replaying the call outside Burp.
- Adds a "Generate .proto stub from decoded body" context menu item, which
  reverse-engineers a best-effort `.proto` message definition (with inferred
  field types and nested messages) from a decoded request or response body
  and copies it to the clipboard, for traffic seen without .proto files,
  a FileDescriptorSet, or Server Reflection.
- Adds a filter box to the "gRPC Methods" suite tab, to narrow the discovered
  method list by host or path substring.
- Adds an "Export CSV..." button to the "gRPC Methods" suite tab, to save the
  (filtered) discovered method list as a CSV file (host, path, observation
  count).
- Persists the "gRPC Methods" discovery log across Burp restarts (per Burp
  project), so passively observed service/method paths survive reloading the
  extension or Burp itself.
- Highlights and annotates Proxy history requests, not just responses, when
  auto-highlight is enabled; the annotation note includes the resolved
  `/pkg.Service/Method` path when one is known.
- Adds a "gRPC Schema" suite tab listing every message type and service
  method currently loaded (from .proto files, a FileDescriptorSet, or Server
  Reflection), with "Copy to clipboard" and "Export .proto..." buttons for
  saving a local record of a target's schema.
- Accepts per-`/pkg.Service/Method` request/response message type overrides
  in Settings, used instead of the single default request/response type when
  no loaded schema resolves that specific path.
- Adds a `hex` field alongside the base64 `value` for decoded `bytes` fields,
  for manual inspection without an external hex tool.
- Flags likely-sensitive values in the decoded JSON with a best-effort `hint`
  field: JWT-shaped strings (`hint: "jwt"`), and, when schema field names are
  available, fields named like credentials (e.g. `password`, `api_key`,
  `access_token`) (`hint: "possible-secret"`). Not a security scanner, just a
  low-precision pointer to values worth a closer look.
- Surfaces the `grpc-timeout` request header as a read-only `grpcTimeout`
  field in the decoded JSON, alongside the existing `grpcStatus`/
  `grpcMessage` response fields.
- Adds a filter box to the "gRPC Schema" suite tab, to narrow the shown
  message types and methods by name/path substring (the "Export .proto..."
  button always exports the full schema regardless of the filter).
- Adds an "Export JSON..." button to the "gRPC Methods" suite tab, alongside
  the existing CSV export, for the (filtered) discovered method list.
- Renders the "gRPC Methods" and "gRPC Schema" suite tabs as sortable tables
  (click a column header to sort) instead of a plain text dump; "Copy to
  clipboard" copies rows in the table's current on-screen sort order.
- Shows an inline status line under the `gRPC Codec` editor when an edited
  message fails to re-encode (e.g. invalid JSON), instead of only logging
  the failure to the extension output; the editor falls back to sending the
  previously decoded body either way, but this makes that fallback visible.

## Build

Requirements:

- JDK 17+
- Maven 3.9+

```sh
mvn test
mvn package
```

Load `target/burp-grpc-codec-0.2.0-SNAPSHOT-burp.jar` in Burp Suite under
`Extensions -> Installed -> Add -> Java`.

## Settings

Open Burp's settings and search for `Burp gRPC Codec` to configure:

- Local `.proto` files or directories, separated by commas. Directories are
  scanned for `.proto` files as well as binary `FileDescriptorSet` files
  (`.protoset`/`.pb`/`.desc`/`.bin`), both loaded the same way.
- A gRPC Server Reflection target as `host:port`, with optional TLS.
- gRPC Server Reflection request timeout, in seconds (default `5`).
- Default request and response message types for schema-aware decoding.
- Raw protobuf detection mode: `broad`, `strict`, or `off`.
- Editor JSON output style: `pretty` (default) or `compact`.
- Verbose logging: log schema reload and decode/encode activity to the
  extension output (off by default).
- Maximum nested message depth to decode (default `24`).
- Auto-highlight gRPC/protobuf traffic in Proxy history (off by default). This
  only matches declared `application/grpc*` or protobuf Content-Types (not
  the raw-detection heuristic), since it runs on every response Burp
  processes.
- Auto-select the `gRPC Codec` tab when a message decodes successfully (off
  by default). The Montoya API has no official way to select a message-viewer
  tab; this works by walking up the Swing component tree to find the
  containing `JTabbedPane` and selecting it there, the same unofficial
  technique other Burp extensions (e.g. Paramalyzer) use. It depends on
  Burp's internal UI structure and could stop working in a future Burp
  release; failures are silently ignored rather than breaking decoding.
- Maximum body size for the schema-less raw-detection heuristic, in bytes
  (default `1048576`, i.e. 1 MiB). Only applies to `broad`/`strict`
  raw-detection modes; declared gRPC/protobuf Content-Types are unaffected.
- Per-method message type overrides, for paths no loaded schema resolves.
  Format: `/pkg.Service/Method=RequestType>ResponseType`, semicolon-separated
  for multiple paths (e.g.
  `/demo.Greeter/SayHello=demo.HelloRequest>demo.HelloResponse`). The
  response type may be omitted (`=RequestType` only). Ignored for paths a
  loaded schema (.proto files, FileDescriptorSet, or Server Reflection)
  already resolves; falls back to the single default request/response type
  above when neither applies.

## JSON Format

Fields are named by their protobuf field number:

```json
{
  "_format": "grpc",
  "messages": [
    {
      "compressed": false,
      "message": {
        "f1": {
          "type": "string",
          "name": "greeting",
          "protoType": "string",
          "value": "hello"
        },
        "f2": {
          "type": "varint",
          "value": 123
        }
      }
    }
  ]
}
```

Supported value types:

- `varint`
- `fixed32`
- `fixed64`
- `string`
- `bytes` (base64)
- `message`

When a field appears multiple times, its value becomes an array of typed field
objects.

Decoded `bytes` fields also carry a `hex` value alongside the base64 `value`.
Decoded `string` fields (and, with schema metadata, any field) may carry a
best-effort, decode-only `hint` (e.g. `"jwt"` for JWT-shaped strings, or
`"possible-secret"` for schema field names like `password`/`api_key`); this
is not written back on encode.

With schema metadata, fields can also carry:

- `enumName`: the symbolic name for an `enum` value (e.g. `"ACTIVE"`). Editing
  `enumName` re-resolves to the matching number on encode; if it can't be
  resolved, the numeric `value` is used instead.
- `oneof`: the name of the `oneof` the field belongs to.
- `map`: `true` when the field is a protobuf `map<K, V>`; each entry decodes
  as a nested message with `f1` (key) and `f2` (value).
- `anyType` / `anyValue`: for `google.protobuf.Any` fields, the resolved
  message type and its decoded contents, when the type is known to the
  schema registry. Read-only; edit the raw `type_url`/`value` fields (`f1`/
  `f2` under `value`) to change what gets re-encoded.
- `readable`: a decode-only convenience value, present for several
  `google.protobuf` well-known types. Always edit the raw fields under
  `value` (not `readable`) to change what gets re-encoded:
  - `Timestamp` -> ISO-8601 string; `Duration` -> `"<seconds>s"`.
  - Wrapper types (`StringValue`, `Int32Value`, `BoolValue`, etc.) -> the
    inner scalar value.
  - `FieldMask` -> comma-joined `paths`.
  - `Struct` / `Value` / `ListValue` -> the equivalent plain JSON
    object/array/scalar/`null`.

When a schema is available, JSON keys may also use the schema's field name
instead of `f<number>` (e.g. `"greeting"` instead of `"f1"`).

When present on the response, `grpc-status` / `grpc-message` HTTP headers are
surfaced as read-only `grpcStatus` / `grpcStatusName` / `grpcMessage` fields
at the root of the decoded JSON (not inside `messages`). When present on the
request, `grpc-timeout` is likewise surfaced as `grpcTimeout`. These reflect
the HTTP headers at decode time and are not written back on encode; edit the
headers directly via Burp's header editor instead.

## Limits

Schema-less protobuf decoding cannot know original semantic types. For example,
the same varint may represent `int32`, `uint64`, `bool`, or an enum. Configure
`.proto` files, Server Reflection, and default message types when semantic
metadata is needed.

Server Reflection currently supports plain/TLS `host:port` targets without
custom authentication headers. If gzip or deflate decompression fails,
compressed message bytes are preserved as base64.

The custom `.proto` text parser does not resolve `import` statements, so
well-known types (`google.protobuf.Any`/`Timestamp`/`Duration`) are only
recognized when a field references them by their fully-qualified name
directly (e.g. `google.protobuf.Timestamp field = 1;`); the message itself
does not need to be defined. Nested `message` declarations (a `message`
inside another `message`) are flattened and are not scoped to their
enclosing type; prefer top-level message declarations, or use gRPC Server
Reflection for accurate nested-type resolution.
