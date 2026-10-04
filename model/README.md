# Model packaging

Model binaries stay outside the APK and outside Git.

Each downloadable model package should contain:

- model binary
- tokenizer assets
- manifest
- checksums

The runtime must verify the checksum before activating a newly downloaded model.

Planned experiments:

- baseline floating-point export
- INT8
- INT4 where accuracy remains acceptable
- vocabulary/tokenizer footprint reduction
- backend-specific graph optimization

Every reduced model must be compared against the same decision corpus before it is considered usable.
