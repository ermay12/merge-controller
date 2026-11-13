#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Report face buttons by their Nintendo label (A = BUTTON_A) instead of by
// position (bottom button = BUTTON_A, Android's default for Joy-Cons).
#define MERGER_FLAG_NINTENDO_LAYOUT 0x1

// Present the virtual controller as an Xbox Wireless Controller (name, IDs,
// axis ranges and buttons) for games that only accept known controllers.
// Read when merging starts; changing it requires a restart.
#define MERGER_FLAG_XBOX_IDENTITY 0x4

typedef struct {
    int flags;
    // evdev device names to merge; NULL selects the hid-nintendo defaults.
    const char *left_name;
    const char *right_name;
    // User button overrides, as flat triples [side, src, dst]: side 0 = left,
    // 1 = right; dst < 0 removes the mapping for that button. Overrides win
    // over the layout presets. Like flags, read when merging starts; changing
    // them requires a restart. NULL means no overrides.
    const int *remap;
    size_t remap_count;  // number of triples, not ints
} merger_config;

// Creates the virtual controller and starts the merge thread.
// Returns 0 on success or a negative errno; details are in merger_status_json().
int merger_start(const merger_config *config);

// Stops merging, releases the Joy-Cons and destroys the virtual controller.
void merger_stop(void);

void merger_set_flags(int flags);

// Writes a JSON object describing the engine state into buf.
void merger_status_json(char *buf, size_t len);

void merger_set_log_stderr(bool enabled);

// Rumble that apps request through the virtual controller, encoded as
// (strong_magnitude << 16) | weak_magnitude. Blocks until the value's sequence
// number differs from `seq` or timeout_ms passes, then returns
// ((uint64_t)sequence << 32) | value.
uint64_t merger_await_rumble(uint32_t seq, int timeout_ms);

// Pops one raw button press (0 -> 1 transition on a grabbed Joy-Con) observed
// since the previous call, oldest first. Returns 0 when none are queued, else
// ((uint64_t)sequence << 32) | (side << 16) | evdev_code. Lets the app identify
// buttons by their real codes, which vary between kernels.
uint64_t merger_take_raw_press(void);

#ifdef __cplusplus
}
#endif
