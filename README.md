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
- Decodes protobuf without `.proto` files using wire-format inference.
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

Load `target/burp-grpc-codec-0.1.0-SNAPSHOT-burp.jar` in Burp Suite under
`Extensions -> Installed -> Add -> Java`.

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
the same varint may represent `int32`, `uint64`, `bool`, or an enum. The decoder
keeps wire-compatible types so edited data can be packed back safely.

Compressed gRPC messages are shown as base64 bytes. The extension preserves and
re-frames them, but it does not decompress or recompress message bodies yet.
