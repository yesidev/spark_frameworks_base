/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.biometrics;

import static android.hardware.biometrics.BiometricManager.Authenticators;

import static com.android.server.biometrics.PreAuthInfo.AUTHENTICATOR_OK;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_DISABLED_BY_DEVICE_POLICY;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_HARDWARE_NOT_DETECTED;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_INSUFFICIENT_STRENGTH;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_INSUFFICIENT_STRENGTH_AFTER_DOWNGRADE;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_NOT_ENABLED_FOR_APPS;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_NOT_ENROLLED;
import static com.android.server.biometrics.PreAuthInfo.BIOMETRIC_NO_HARDWARE;
import static com.android.server.biometrics.PreAuthInfo.CREDENTIAL_NOT_ENROLLED;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricPrompt.AuthenticationResultType;
import android.hardware.biometrics.PromptInfo;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

public class Utils {
    public static boolean isDebugEnabled(Context context, int targetUserId) {
        if (targetUserId == UserHandle.USER_NULL) {
            return false;
        }

        if (!(Build.IS_ENG || Build.IS_USERDEBUG)) {
            return false;
        }

        if (Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.BIOMETRIC_DEBUG_ENABLED, 0,
                targetUserId) == 0) {
            return false;
        }
        return true;
    }

    /**
     * Combines {@link PromptInfo#setDeviceCredentialAllowed(boolean)} with
     * {@link PromptInfo#setAuthenticators(int)}, as the former is not flexible enough.
     */
    static void combineAuthenticatorBundles(PromptInfo promptInfo) {
        // Cache and remove explicit ALLOW_DEVICE_CREDENTIAL boolean flag from the bundle.
        final boolean deviceCredentialAllowed = promptInfo.isDeviceCredentialAllowed();
        promptInfo.setDeviceCredentialAllowed(false);

        final @Authenticators.Types int authenticators;
        if (promptInfo.getAuthenticators() != 0) {
            // Ignore ALLOW_DEVICE_CREDENTIAL flag if AUTH_TYPES_ALLOWED is defined.
            authenticators = promptInfo.getAuthenticators();
        } else {
            // Otherwise, use ALLOW_DEVICE_CREDENTIAL flag along with Weak+ biometrics by default.
            authenticators = deviceCredentialAllowed
                    ? Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK
                    : Authenticators.BIOMETRIC_WEAK;
        }

        promptInfo.setAuthenticators(authenticators);
    }

    /**
     * @param authenticators composed of one or more values from {@link Authenticators}
     * @return true if device credential is allowed.
     */
    static boolean isCredentialRequested(@Authenticators.Types int authenticators) {
        return (authenticators & Authenticators.DEVICE_CREDENTIAL) != 0;
    }

    /**
     * @param promptInfo should be first processed by
     * {@link #combineAuthenticatorBundles(PromptInfo)}
     * @return true if device credential is allowed.
     */
    static boolean isCredentialRequested(PromptInfo promptInfo) {
        return isCredentialRequested(promptInfo.getAuthenticators());
    }

    /**
     * Checks if any of the publicly defined strengths are set.
     *
     * @param authenticators composed of one or more values from {@link Authenticators}
     * @return minimal allowed biometric strength or 0 if biometric authentication is not allowed.
     */
    static int getPublicBiometricStrength(@Authenticators.Types int authenticators) {
        // Only biometrics WEAK and above are allowed to integrate with the public APIs.
        return authenticators & Authenticators.BIOMETRIC_WEAK;
    }

    /**
     * Checks if any of the publicly defined strengths are set.
     *
     * @param promptInfo should be first processed by
     * {@link #combineAuthenticatorBundles(PromptInfo)}
     * @return minimal allowed biometric strength or 0 if biometric authentication is not allowed.
     */
    static int getPublicBiometricStrength(PromptInfo promptInfo) {
        return getPublicBiometricStrength(promptInfo.getAuthenticators());
    }

    /**
     * Checks if any of the publicly defined strengths are set.
     *
     * @param promptInfo should be first processed by
     * {@link #combineAuthenticatorBundles(PromptInfo)}
     * @return true if biometric authentication is allowed.
     */
    static boolean isBiometricRequested(PromptInfo promptInfo) {
        return getPublicBiometricStrength(promptInfo) != 0;
    }

    /**
     * @param sensorStrength the strength of the sensor
     * @param requestedStrength the strength that it must meet
     * @return true only if the sensor is at least as strong as the requested strength
     */
    public static boolean isAtLeastStrength(int sensorStrength, int requestedStrength) {
        // Clear out any bits that are not reserved for biometric
        sensorStrength &= Authenticators.BIOMETRIC_MIN_STRENGTH;

        // If the authenticator contains bits outside of the requested strength, it is too weak.
        if ((sensorStrength & ~requestedStrength) != 0) {
            return false;
        }

        for (int i = Authenticators.BIOMETRIC_MAX_STRENGTH;
                i <= requestedStrength; i = (i << 1) | 1) {
            if (i == sensorStrength) {
                return true;
            }
        }

        Slog.e(BiometricService.TAG, "Unknown sensorStrength: " + sensorStrength
                + ", requestedStrength: " + requestedStrength);
        return false;
    }

    /**
     * Checks if the authenticator configuration is a valid combination of the public APIs
     * @param promptInfo
     * @return
     */
    static boolean isValidAuthenticatorConfig(PromptInfo promptInfo) {
        final int authenticators = promptInfo.getAuthenticators();
        return isValidAuthenticatorConfig(authenticators);
    }

    /**
     * Checks if the authenticator configuration is a valid combination of the public APIs
     * @param authenticators
     * @return
     */
    static boolean isValidAuthenticatorConfig(int authenticators) {
        // The caller is not required to set the authenticators. But if they do, check the below.
        if (authenticators == 0) {
            return true;
        }

        // Check if any of the non-biometric and non-credential bits are set. If so, this is
        // invalid.
        final int testBits = ~(Authenticators.DEVICE_CREDENTIAL
                | Authenticators.BIOMETRIC_MIN_STRENGTH);
        if ((authenticators & testBits) != 0) {
            Slog.e(BiometricService.TAG, "Non-biometric, non-credential bits found."
                    + " Authenticators: " + authenticators);
            return false;
        }

        // Check that biometrics bits are either NONE, WEAK, or STRONG. If NONE, DEVICE_CREDENTIAL
        // should be set.
        final int biometricBits = authenticators & Authenticators.BIOMETRIC_MIN_STRENGTH;
        if (biometricBits == Authenticators.EMPTY_SET
                && isCredentialRequested(authenticators)) {
            return true;
        } else if (biometricBits == Authenticators.BIOMETRIC_STRONG) {
            return true;
        } else if (biometricBits == Authenticators.BIOMETRIC_WEAK) {
            return true;
        }

        Slog.e(BiometricService.TAG, "Unsupported biometric flags. Authenticators: "
                + authenticators);
        // Non-supported biometric flags are being used
        return false;
    }

    /**
     * Converts error codes from BiometricConstants, which are used in most of the internal plumbing
     * and eventually returned to {@link BiometricPrompt.AuthenticationCallback} to public
     * {@link BiometricManager} constants, which are used by APIs such as
     * {@link BiometricManager#canAuthenticate(int)}
     *
     * @param biometricConstantsCode see {@link BiometricConstants}
     * @return see {@link BiometricManager}
     */
    static int biometricConstantsToBiometricManager(int biometricConstantsCode) {
        final int biometricManagerCode;

        switch (biometricConstantsCode) {
            case BiometricConstants.BIOMETRIC_SUCCESS:
                biometricManagerCode = BiometricManager.BIOMETRIC_SUCCESS;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS:
            case BiometricConstants.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED;
                break;
            default:
                Slog.e(BiometricService.TAG, "Unhandled result code: " + biometricConstantsCode);
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;
                break;
        }
        return biometricManagerCode;
    }

    /**
     * Converts a {@link BiometricPrompt} dismissal reason to an authentication type at the level of
     * granularity supported by {@link BiometricPrompt.AuthenticationResult}.
     *
     * @param reason The reason that the {@link BiometricPrompt} was dismissed. Must be one of:
     *               {@link BiometricPrompt#DISMISSED_REASON_CREDENTIAL_CONFIRMED},
     *               {@link BiometricPrompt#DISMISSED_REASON_BIOMETRIC_CONFIRMED}, or
     *               {@link BiometricPrompt#DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED}
     * @return An integer representing the authentication type for {@link
     *         BiometricPrompt.AuthenticationResult}.
     * @throws IllegalArgumentException if given an invalid dismissal reason.
     */
    static @AuthenticationResultType int getAuthenticationTypeForResult(int reason) {
        switch (reason) {
            case BiometricPrompt.DISMISSED_REASON_CREDENTIAL_CONFIRMED:
                return BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL;

            case BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED:
            case BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED:
                return BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC;

            default:
                throw new IllegalArgumentException("Unsupported dismissal reason: " + reason);
        }
    }


    static int authenticatorStatusToBiometricConstant(
            @PreAuthInfo.AuthenticatorStatus int status) {
        switch (status) {
            case BIOMETRIC_NO_HARDWARE:
            case BIOMETRIC_INSUFFICIENT_STRENGTH:
                return BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT;

            case AUTHENTICATOR_OK:
                return BiometricConstants.BIOMETRIC_SUCCESS;

            case BIOMETRIC_INSUFFICIENT_STRENGTH_AFTER_DOWNGRADE:
                return BiometricConstants.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED;

            case BIOMETRIC_NOT_ENROLLED:
                return BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS;

            case CREDENTIAL_NOT_ENROLLED:
                return BiometricConstants.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL;

            case BIOMETRIC_DISABLED_BY_DEVICE_POLICY:
            case BIOMETRIC_HARDWARE_NOT_DETECTED:
            case BIOMETRIC_NOT_ENABLED_FOR_APPS:
            default:
                return BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE;
        }
    }

    static boolean isConfirmationSupported(@BiometricAuthenticator.Modality int modality) {
        switch (modality) {
            case BiometricAuthenticator.TYPE_FACE:
            case BiometricAuthenticator.TYPE_IRIS:
                return true;
            default:
                return false;
        }
    }

    static int removeBiometricBits(@Authenticators.Types int authenticators) {
        return authenticators & ~Authenticators.BIOMETRIC_MIN_STRENGTH;
    }
}
