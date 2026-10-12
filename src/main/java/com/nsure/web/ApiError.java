package com.nsure.web;

public record ApiError(int status, String error, String message) {
}
