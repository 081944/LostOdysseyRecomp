#pragma once

namespace android_companion {
// Called on the guest engine thread after map/position snapshots are updated.
// Does no work when no Android companion display is listening.
void Publish();
}
