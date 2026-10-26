package com.talenti.exam.domain;

public enum AttemptStatus {
	NOT_STARTED, IN_PROGRESS, SUBMITTED, TIMED_OUT, CLOSED_BY_RULE, CLOSED_WITHDRAWN;
	public boolean closed() { return this != NOT_STARTED && this != IN_PROGRESS; }
}
