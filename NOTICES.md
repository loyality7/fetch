# Third-Party Open Source Notices & Dependency Audit

This document records third-party open-source libraries, dependencies, and licences used by the `fetch` engine and service components.

## Dependency Audit

All third-party dependencies are open-source and released under permissive, commercially compatible licences (Apache 2.0, MIT). No GPL/AGPL or copyleft licensed libraries are included in the repository.

| Library / Module | Developer / Maintainer | Version | Licence |
| :--- | :--- | :--- | :--- |
| `kotlin-stdlib` | JetBrains | 2.1.0 | Apache License 2.0 |
| `kotlinx-coroutines` (`core`, `android`, `test`) | JetBrains | 1.9.0 | Apache License 2.0 |
| `kotlinx-serialization-json` | JetBrains | 1.7.3 | Apache License 2.0 |
| `okhttp` (`okhttp`, `mockwebserver`) | Square, Inc. | 4.12.0 | Apache License 2.0 |
| `jsoup` | Jonathan Hedley | 1.18.3 | MIT License |
| `androidx.sqlite` (`sqlite-bundled`, `sqlite`) | Google / AndroidX | 2.5.0 | Apache License 2.0 |
| `ktor-server` (`core`, `cio`, `content-negotiation`, `serialization`, `status-pages`, `sse`) | JetBrains | 3.0.3 | Apache License 2.0 |
| `androidx.core` / `androidx.annotation` | Google / AndroidX | 1.13.1 / 1.9.1 | Apache License 2.0 |
| `junit` | JUnit | 4.13.2 | Eclipse Public License 1.0 |
| `androidx.test` (`junit`, `runner`) | Google / AndroidX | 1.2.1 / 1.6.2 | Apache License 2.0 |

---

## Licence Texts

### Apache License 2.0
Used by: Kotlin Standard Library, Kotlinx Coroutines, Kotlinx Serialization, OkHttp, AndroidX SQLite, Ktor Server, AndroidX Core, AndroidX Annotation, AndroidX Test.

```
Apache License
Version 2.0, January 2004
http://www.apache.org/licenses/

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.
See the License for the specific language governing permissions and
limitations under the License.
```

### MIT License
Used by: jsoup HTML Parser.

```
The MIT License

Copyright (c) 2009-2024 Jonathan Hedley (https://jsoup.org/)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Codebase Audit Summary
- **Third-Party Source Code**: Verified **0** embedded third-party source files inside the repository.
- **Copyleft / GPL Risk**: Verified **0** GPL/AGPL copyleft dependencies. All build artifacts utilize permissive Apache-2.0 and MIT licensed components.
