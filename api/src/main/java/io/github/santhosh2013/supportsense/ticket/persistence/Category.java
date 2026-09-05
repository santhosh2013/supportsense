package io.github.santhosh2013.supportsense.ticket.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String slug;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @Column(name = "required_skill")
    private String requiredSkill;

    @Column(name = "default_priority")
    private String defaultPriority;

    /**
     * Category-level guard, paired with (and never a substitute for) the deterministic
     * keyword pre-screen — see ADR-0009. Keyed off the *predicted* category, so it is
     * insufficient alone against misclassification.
     */
    @Column(name = "auto_answer_blocked", nullable = false)
    private boolean autoAnswerBlocked;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Category() {
        // JPA
    }

    public Category(
            String name,
            String slug,
            Category parent,
            Team team,
            String requiredSkill,
            String defaultPriority,
            boolean autoAnswerBlocked) {
        this.name = name;
        this.slug = slug;
        this.parent = parent;
        this.team = team;
        this.requiredSkill = requiredSkill;
        this.defaultPriority = defaultPriority;
        this.autoAnswerBlocked = autoAnswerBlocked;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public Category getParent() {
        return parent;
    }

    public Team getTeam() {
        return team;
    }

    public String getRequiredSkill() {
        return requiredSkill;
    }

    public String getDefaultPriority() {
        return defaultPriority;
    }

    public boolean isAutoAnswerBlocked() {
        return autoAnswerBlocked;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
