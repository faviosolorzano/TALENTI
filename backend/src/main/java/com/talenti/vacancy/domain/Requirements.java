package com.talenti.vacancy.domain;

import java.util.List;

public record Requirements(List<Requirement> required, List<Requirement> desirable) {
}
