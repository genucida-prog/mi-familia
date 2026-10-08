# Nothing is minified in this build, so this file is intentionally empty.
#
# If you later switch `isMinifyEnabled` to true in the `release` build type,
# you do NOT need to add keep rules for the `@JavascriptInterface` bridge:
# R8 keeps members annotated with `@JavascriptInterface` by default, and the
# bridge is reached from Kotlin code (MainActivity), not reflection.