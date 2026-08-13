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
- Loads optional schema metadata from local `.proto` files or gRPC Server
  Reflection.
- Decodes protobuf without `.proto` files using wire-format inference.
- Adds schema metadata such as field names and proto types while keeping the
  editable `f<number>` JSON format.
- Selects request and response message types from gRPC service/method paths
  when service definitions are available.
- Decodes and re-encodes packed repeated scalar fields when schema metadata is
  available.
- Decompresses and recompresses gzip-compressed gRPC messages when
  `grpc-encoding: gzip` is present.
- Preserves repeated fields and unknown numeric field numbers.
- Re-encodes edited JSON back into protobuf and restores the original transport
  envelope.

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

- Local `.proto` files or directories, separated by commas.
- A gRPC Server Reflection target as `host:port`, with optional TLS.
- Default request and response message types for schema-aware decoding.
- Raw protobuf detection mode: `broad`, `strict`, or `off`.

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

## Limits

Schema-less protobuf decoding cannot know original semantic types. For example,
the same varint may represent `int32`, `uint64`, `bool`, or an enum. Configure
`.proto` files, Server Reflection, and default message types when semantic
metadata is needed.

Server Reflection currently supports plain/TLS `host:port` targets without
custom authentication headers. If gzip decompression fails, compressed message
bytes are preserved as base64.
