#pragma once

#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Report face buttons by their Nintendo label (A = BUTTON_A) instead of by
// position (bottom button = BUTTON_A, Android's default for Joy-Cons).
#define MERGER_FLAG_NINTENDO_LAYOUT 0x1

typedef struct {
    int flags;
    // evdev device names to merge; NULL selects the hid-nintendo defaults.
    const char *left_name;
    const char *right_name;
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

#ifdef __cplusplus
}
#endif
