package io.github.santhosh2013.supportsense.triage.domain;

/** A2 supplies the LLM-backed continuation; A1's gate can short-circuit it safely. */
@FunctionalInterface
public interface TriageContinuation<T> {

    T continueTriage();
}